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

    expect(await screen.findByRole('status')).toHaveTextContent('RFD-0123456789AB approved.');
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

    expect(await screen.findByRole('status')).toHaveTextContent('RFD-0123456789AB rejected.');
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

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Decision not recorded: A refund request cannot be approved by the person who requested it (SELF_APPROVAL_FORBIDDEN)',
    );
  });

  it('shows an empty queue', async () => {
    queue();
    renderWithProviders(<RefundApprovalsPage />);
    expect(await screen.findByText('No refund requests are waiting.')).toBeInTheDocument();
  });
});
