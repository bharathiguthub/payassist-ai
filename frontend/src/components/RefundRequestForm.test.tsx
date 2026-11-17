import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RefundRequestForm } from './RefundRequestForm';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { refundRequest } from '../test/refund-fixtures';

type Captured = { body: unknown; key: string | null; csrf: string | null };

function captureCreates(respond: (n: number) => Response) {
  const calls: Captured[] = [];
  server.use(
    http.post('/api/v1/refunds', async ({ request }) => {
      calls.push({
        body: await request.json(),
        key: request.headers.get('Idempotency-Key'),
        csrf: request.headers.get('X-XSRF-TOKEN'),
      });
      return respond(calls.length);
    }),
  );
  return calls;
}

async function fillAndSubmit(amount: string, reason?: string) {
  const input = screen.getByLabelText('Refund amount (USD)');
  await userEvent.clear(input);
  await userEvent.type(input, amount);
  if (reason) await userEvent.selectOptions(screen.getByLabelText('Reason'), reason);
  await userEvent.click(screen.getByRole('button', { name: 'Submit for approval' }));
}

describe('RefundRequestForm', () => {
  it('submits a decimal-string amount with an idempotency key and the CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie-value';
    const calls = captureCreates(() => HttpResponse.json(refundRequest(), { status: 201 }));
    renderWithProviders(<RefundRequestForm paymentRef="PAY-000001" currency="USD" />);

    await fillAndSubmit('25.50', 'DUPLICATE_CHARGE');

    expect(await screen.findByRole('status')).toHaveTextContent(/RFD-0123456789AB.*Pending approval/);
    expect(screen.getByRole('status')).toHaveTextContent(/No money moves until a supervisor approves it/);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ paymentRef: 'PAY-000001', amount: '25.50', currency: 'USD', reason: 'DUPLICATE_CHARGE' });
    expect(calls[0]!.key).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    expect(calls[0]!.csrf).toBe('csrf-cookie-value');
  });

  it('reuses the idempotency key when the same payload is retried, and uses a new one for a new payload', async () => {
    const calls = captureCreates((n) =>
      n === 1 ? HttpResponse.json({ title: 'Unavailable', status: 503 }, { status: 503 }) : HttpResponse.json(refundRequest()),
    );
    renderWithProviders(<RefundRequestForm paymentRef="PAY-000001" currency="USD" />);

    await fillAndSubmit('10.00');
    expect(await screen.findByRole('alert')).toHaveTextContent(/Refund request not submitted/);
    await userEvent.click(screen.getByRole('button', { name: 'Submit for approval' }));
    await screen.findByRole('status');
    await fillAndSubmit('11.00');
    await vi.waitFor(() => expect(calls).toHaveLength(3));

    expect(calls[1]!.key).toBe(calls[0]!.key);
    expect(calls[2]!.key).not.toBe(calls[1]!.key);
  });

  it('shows the refusal code and eligibility reasons', async () => {
    captureCreates(() =>
      HttpResponse.json(
        {
          title: 'Business rule violated',
          status: 422,
          detail: 'The refund is not eligible: AMOUNT_EXCEEDS_REFUNDABLE, PENDING_REFUND_IN_PROGRESS',
          code: 'REFUND_NOT_ELIGIBLE',
          reasons: ['AMOUNT_EXCEEDS_REFUNDABLE', 'PENDING_REFUND_IN_PROGRESS'],
        },
        { status: 422 },
      ),
    );
    renderWithProviders(<RefundRequestForm paymentRef="PAY-000001" currency="USD" />);

    await fillAndSubmit('500');

    expect(await screen.findByRole('alert')).toHaveTextContent('(REFUND_NOT_ELIGIBLE)');
    const reasons = screen.getByRole('list', { name: 'Refusal reasons' });
    expect(within(reasons).getByText('AMOUNT_EXCEEDS_REFUNDABLE')).toBeInTheDocument();
    expect(within(reasons).getByText('PENDING_REFUND_IN_PROGRESS')).toBeInTheDocument();
  });

  it('validates the amount format before calling the API', async () => {
    const calls = captureCreates(() => HttpResponse.json(refundRequest()));
    renderWithProviders(<RefundRequestForm paymentRef="PAY-000001" currency="USD" />);

    await fillAndSubmit('12,50');

    expect(await screen.findByRole('alert')).toHaveTextContent(/Enter the amount to refund/);
    expect(calls).toHaveLength(0);
  });
});
