import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RefundApprovalsPage } from './RefundApprovalsPage';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { refundRequest } from '../test/refund-fixtures';

function queue(...items: ReturnType<typeof refundRequest>[]) {
  server.use(
    http.get('/api/v1/refunds', ({ request }) => {
      const statuses = new URL(request.url).searchParams.getAll('status');
      expect(statuses).toEqual(['PENDING_APPROVAL', 'ESCALATION_REQUIRED']);
      return HttpResponse.json({ content: items, page: 0, size: 50, totalElements: items.length, totalPages: 1 });
    }),
  );
}

describe('RefundApprovalsPage', () => {
  it('approves with the reviewed version and the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie-value';
    queue(refundRequest({ version: 3 }));
    let sent: { body: unknown; csrf: string | null } | undefined;
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/approve', async ({ request }) => {
        sent = { body: await request.json(), csrf: request.headers.get('X-XSRF-TOKEN') };
        return HttpResponse.json(refundRequest({ status: 'APPROVED', allowedActions: [], version: 4 }));
      }),
    );
    renderWithProviders(<RefundApprovalsPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Approve RFD-0123456789AB' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Approve RFD-0123456789AB?' });
    expect(dialog).toHaveTextContent('cannot be undone');
    expect(sent).toBeUndefined(); // nothing is sent before confirmation
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve refund' }));

    expect(await screen.findByText('RFD-0123456789AB approved.')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(sent).toEqual({ body: { expectedVersion: 3 }, csrf: 'csrf-cookie-value' });
  });

  it('rejects with the chosen reason', async () => {
    queue(refundRequest({ status: 'ESCALATION_REQUIRED', allowedActions: ['REJECT'] }));
    let body: unknown;
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/reject', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(refundRequest({ status: 'REJECTED', allowedActions: [] }));
      }),
    );
    renderWithProviders(<RefundApprovalsPage />);

    expect(await screen.findByText('Escalation required')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Rejection reason for RFD-0123456789AB'), 'ESCALATION_NOT_AVAILABLE');
    await userEvent.click(screen.getByRole('button', { name: 'Reject RFD-0123456789AB' }));
    await userEvent.click(screen.getByRole('button', { name: 'Reject refund' }));

    expect(await screen.findByText('RFD-0123456789AB rejected.')).toBeInTheDocument();
    expect(body).toEqual({ reason: 'ESCALATION_NOT_AVAILABLE', expectedVersion: 0 });
  });

  it('shows no actions where the server allows none (own request, or read-only admin)', async () => {
    queue(refundRequest({ allowedActions: [] }));
    renderWithProviders(<RefundApprovalsPage />);

    const table = await screen.findByRole('table', { name: 'Refund approval queue' });
    expect(within(table).getByText('No action available')).toBeInTheDocument();
    expect(within(table).queryByRole('button')).not.toBeInTheDocument();
  });

  it('explains a refused decision with its code', async () => {
    queue(refundRequest());
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/approve', () =>
        HttpResponse.json(
          {
            title: 'Action not permitted',
            status: 403,
            detail: 'A refund request cannot be approved by the person who requested it',
            code: 'SELF_APPROVAL_FORBIDDEN',
          },
          { status: 403 },
        ),
      ),
    );
    renderWithProviders(<RefundApprovalsPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Approve RFD-0123456789AB' }));
    await userEvent.click(screen.getByRole('button', { name: 'Approve refund' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Decision not recorded: You cannot decide on a refund you requested yourself. Another supervisor must decide. (SELF_APPROVAL_FORBIDDEN)',
    );
  });

  it('cancels a decision with Escape without sending anything', async () => {
    queue(refundRequest());
    let called = false;
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/approve', () => {
        called = true;
        return HttpResponse.json(refundRequest({ status: 'APPROVED' }));
      }),
    );
    renderWithProviders(<RefundApprovalsPage />);

    const approve = await screen.findByRole('button', { name: 'Approve RFD-0123456789AB' });
    await userEvent.click(approve);
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(approve).toHaveFocus();
    expect(called).toBe(false);
  });

  it('explains a decision that was overtaken and offers a reload', async () => {
    queue(refundRequest());
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/approve', () =>
        HttpResponse.json({ status: 409, title: 'Concurrent modification', code: 'CONCURRENT_MODIFICATION' }, { status: 409 }),
      ),
    );
    renderWithProviders(<RefundApprovalsPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Approve RFD-0123456789AB' }));
    await userEvent.click(screen.getByRole('button', { name: 'Approve refund' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Someone else changed this record');
    expect(within(alert).getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(alert).toHaveFocus();
  });

  it('shows a database refusal with its support reference but no database details', async () => {
    queue(refundRequest());
    server.use(
      http.post('/api/v1/refunds/RFD-0123456789AB/approve', () =>
        HttpResponse.json(
          { status: 409, title: 'Record protected', code: 'RECORD_PROTECTED', correlationId: 'cid-0001-abcd' },
          { status: 409 },
        ),
      ),
    );
    renderWithProviders(<RefundApprovalsPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Approve RFD-0123456789AB' }));
    await userEvent.click(screen.getByRole('button', { name: 'Approve refund' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This record is protected');
    expect(alert).toHaveTextContent('Reference: cid-0001-abcd');
  });

  it('shows an empty queue', async () => {
    queue();
    renderWithProviders(<RefundApprovalsPage />);
    expect(await screen.findByText('No refund requests are waiting.')).toBeInTheDocument();
  });
});
