import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { DisputeReviewPage, DisputesPage } from './DisputesPage';
import { OpenDisputeForm } from '../components/OpenDisputeForm';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { disputeView } from '../test/dispute-fixtures';

function list(capture: URL[], overdue = false) {
  server.use(
    http.get('/api/v1/disputes', ({ request }) => {
      capture.push(new URL(request.url));
      const d = disputeView({ overdue, responseDueAt: overdue ? '2026-11-10T00:00:00Z' : null });
      return HttpResponse.json({ content: [d], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    }),
  );
}

describe('Dispute list and review queue', () => {
  it('lists cases with deadline as provided and sends filters to the API', async () => {
    const urls: URL[] = [];
    list(urls, true);
    renderWithProviders(<DisputesPage />);

    const table = await screen.findByRole('table', { name: 'Dispute cases' });
    expect(within(table).getByRole('link', { name: 'DSP-0123456789AB' })).toHaveAttribute('href', '/disputes/DSP-0123456789AB');
    expect(within(table).getByText('Overdue')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Payment reference'), 'pay-000072');
    await userEvent.selectOptions(screen.getByLabelText('Case status'), 'PENDING_REVIEW');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await userEvent.click(screen.getByLabelText('Overdue only'));

    await vi.waitFor(() => {
      const last = urls.at(-1)!;
      expect(last.searchParams.get('paymentRef')).toBe('PAY-000072');
      expect(last.searchParams.getAll('status')).toEqual(['PENDING_REVIEW']);
      expect(last.searchParams.get('overdue')).toBe('true');
    });
  });

  it('shows "Not provided" when no deadline was given (PayAssist never computes one)', async () => {
    list([]);
    renderWithProviders(<DisputesPage />);
    expect(await screen.findByText('Not provided')).toBeInTheDocument();
  });

  it('review queue asks for cases awaiting a supervisor', async () => {
    const urls: URL[] = [];
    list(urls);
    renderWithProviders(<DisputeReviewPage />);

    expect(await screen.findByRole('heading', { name: 'Dispute review' })).toBeInTheDocument();
    await vi.waitFor(() => expect(urls[0]!.searchParams.getAll('status')).toEqual(['PENDING_REVIEW', 'EVIDENCE_SUBMITTED']));
  });
});

describe('OpenDisputeForm', () => {
  it('opens a case with an idempotency key and the deadline as entered (UTC)', async () => {
    let sent: { body: unknown; key: string | null } | undefined;
    server.use(
      http.post('/api/v1/disputes', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') };
        return HttpResponse.json(disputeView({ status: 'OPEN' }), { status: 201 });
      }),
    );
    renderWithProviders(<OpenDisputeForm paymentRef="PAY-000072" />);

    await userEvent.selectOptions(screen.getByLabelText('Category'), 'FRAUDULENT');
    await userEvent.type(screen.getByLabelText(/Network reason code/), '10.4');
    fireEvent.change(screen.getByLabelText(/Response due, UTC/), { target: { value: '2026-12-01T17:30' } });
    await userEvent.click(screen.getByRole('button', { name: 'Open dispute case' }));

    expect(await screen.findByRole('link', { name: 'DSP-0123456789AB' })).toHaveAttribute('href', '/disputes/DSP-0123456789AB');
    expect(sent!.body).toEqual({
      paymentRef: 'PAY-000072',
      reasonCategory: 'FRAUDULENT',
      networkReasonCode: '10.4',
      responseDueAt: '2026-12-01T17:30:00Z',
    });
    expect(sent!.key).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  });

  it('explains when the payment already has an active case', async () => {
    server.use(
      http.post('/api/v1/disputes', () =>
        HttpResponse.json(
          { title: 'Conflict with current state', status: 409, detail: 'Payment PAY-000072 already has an active dispute case', code: 'DISPUTE_ALREADY_OPEN' },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(<OpenDisputeForm paymentRef="PAY-000072" />);
    await userEvent.click(screen.getByRole('button', { name: 'Open dispute case' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('(DISPUTE_ALREADY_OPEN)');
  });

  it('rejects malformed network codes before calling the API', async () => {
    renderWithProviders(<OpenDisputeForm paymentRef="PAY-000072" />);
    await userEvent.type(screen.getByLabelText(/Network reason code/), 'has spaces');
    await userEvent.click(screen.getByRole('button', { name: 'Open dispute case' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/up to 16 letters/);
  });
});
