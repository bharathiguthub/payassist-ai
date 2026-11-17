import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { DisputeCasePage } from './DisputeCasePage';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { disputeView } from '../test/dispute-fixtures';
import type { DisputeView } from '../api/types';

const URL = '/api/v1/disputes/DSP-0123456789AB';

function renderCase(view: DisputeView) {
  server.use(http.get(URL, () => HttpResponse.json(view)));
  return renderWithProviders(
    <Routes>
      <Route path="/disputes/:caseRef" element={<DisputeCasePage />} />
    </Routes>,
    { route: '/disputes/DSP-0123456789AB' },
  );
}

describe('DisputeCasePage', () => {
  it('renders notes as plain text, never as HTML', async () => {
    const markup = '<img src=x onerror="alert(1)"> customer upset\nsecond line';
    const { container } = renderCase(disputeView({ notes: [{ body: markup, author: 'agent.alice', createdAt: '2026-11-16T11:00:00Z' }] }));

    const notes = await screen.findByRole('list', { name: 'Notes' });
    expect(within(notes).getByText(/<img src=x onerror="alert\(1\)"> customer upset/)).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
  });

  it('shows only the actions the server allows', async () => {
    renderCase(disputeView({ status: 'PENDING_REVIEW', allowedActions: ['ADD_NOTE', 'ADD_EVIDENCE', 'RETURN_FOR_INVESTIGATION', 'WITHDRAW'] }));

    expect(await screen.findByRole('button', { name: 'Return for investigation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record withdrawal' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve evidence submission' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Accept liability/ })).not.toBeInTheDocument();
  });

  it('shows a read-only case when no actions are allowed (admin)', async () => {
    renderCase(disputeView({ allowedActions: [] }));
    expect(await screen.findByText('No actions are available to you on this case.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add note' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add evidence' })).not.toBeInTheDocument();
  });

  it('sends transitions with the reviewed version and the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie-value';
    let sent: { body: unknown; csrf: string | null } | undefined;
    server.use(
      http.post(`${URL}/record-outcome`, async ({ request }) => {
        sent = { body: await request.json(), csrf: request.headers.get('X-XSRF-TOKEN') };
        return HttpResponse.json(disputeView({ status: 'WON', allowedActions: [], version: 6 }));
      }),
    );
    renderCase(disputeView({ status: 'EVIDENCE_SUBMITTED', allowedActions: ['ADD_NOTE', 'ADD_EVIDENCE', 'RECORD_OUTCOME', 'WITHDRAW'], version: 5 }));

    await userEvent.selectOptions(await screen.findByLabelText('Outcome reported by the bank or network'), 'LOST');
    await userEvent.click(screen.getByRole('button', { name: 'Record outcome' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Case is now won.');
    expect(sent).toEqual({ body: { outcome: 'LOST', expectedVersion: 5 }, csrf: 'csrf-cookie-value' });
  });

  it('reuses the note idempotency key when the same note is retried', async () => {
    const keys: (string | null)[] = [];
    server.use(
      http.post(`${URL}/notes`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'));
        return keys.length === 1
          ? HttpResponse.json({ title: 'Unavailable', status: 503 }, { status: 503 })
          : HttpResponse.json(disputeView({ notes: [{ body: 'Called the courier', author: 'agent.alice', createdAt: '2026-11-16T11:00:00Z' }] }));
      }),
    );
    renderCase(disputeView());

    await userEvent.type(await screen.findByLabelText(/Add a note/), 'Called the courier');
    await userEvent.click(screen.getByRole('button', { name: 'Add note' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Not saved');
    await userEvent.click(screen.getByRole('button', { name: 'Add note' }));

    expect(await screen.findByText('Called the courier')).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('explains business-rule refusals with their code', async () => {
    server.use(
      http.post(`${URL}/approve-submission`, () =>
        HttpResponse.json(
          { title: 'Action not permitted', status: 403, detail: 'Approve someone else’s review', code: 'SELF_REVIEW_FORBIDDEN' },
          { status: 403 },
        ),
      ),
    );
    renderCase(disputeView({ status: 'PENDING_REVIEW', allowedActions: ['APPROVE_SUBMISSION'] }));

    await userEvent.click(await screen.findByRole('button', { name: 'Approve evidence submission' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('(SELF_REVIEW_FORBIDDEN)');
  });

  it('warns about pending refunds that cannot be approved during the dispute', async () => {
    renderCase(disputeView({ pendingRefunds: [{ refundRef: 'RFD-0123456789AB', amount: 100, currency: 'GBP', status: 'PENDING_APPROVAL' }] }));
    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('Pending refund requests on this payment');
    expect(note).toHaveTextContent('RFD-0123456789AB');
  });

  it('reports a missing case', async () => {
    server.use(http.get(URL, () => HttpResponse.json({ title: 'Resource not found', status: 404 }, { status: 404 })));
    renderWithProviders(
      <Routes>
        <Route path="/disputes/:caseRef" element={<DisputeCasePage />} />
      </Routes>,
      { route: '/disputes/DSP-0123456789AB' },
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Dispute case not found.');
  });
});
