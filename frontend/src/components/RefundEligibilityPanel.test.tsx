import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { RefundEligibilityPanel } from './RefundEligibilityPanel';
import type { RefundEligibility } from '../api/types';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';

const URL = '/api/v1/refunds/eligibility-check';

const eligible: RefundEligibility = {
  schemaVersion: '1.0',
  decision: 'ELIGIBLE',
  paymentRef: 'PAY-000001',
  requestedAmount: 25.5,
  currency: 'GBP',
  paymentStatus: 'SETTLED',
  paymentMethod: 'CARD',
  paymentAmount: 100,
  paymentCurrency: 'GBP',
  previouslyRefundedAmount: 0,
  reservedPendingAmount: 0,
  remainingRefundableAmount: 100,
  refundWindow: { basis: 'CAPTURED_AT', startsAt: '2026-10-01T10:00:00Z', endsAt: '2027-01-29T10:00:00Z', days: 120, open: true },
  reasons: [{ code: 'WITHIN_POLICY', outcome: 'PASS', message: 'The refund meets every rule of the refund policy.' }],
  checks: [],
  policyVersion: 'SYNTHETIC-REFUND-POLICY-2026.11-v1',
  evaluatedAt: '2026-11-16T12:00:00Z',
  paymentVersion: 4,
  correlationId: 'corr-1',
  advisory: 'Advisory check only: no refund was created, reserved or executed.',
};

function renderPanel() {
  return renderWithProviders(<RefundEligibilityPanel paymentRef="PAY-000001" currency="GBP" />);
}

describe('RefundEligibilityPanel', () => {
  it('posts the amount as a decimal string with the CSRF header and shows the decision', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-from-cookie';
    let received: { body: unknown; csrf: string | null; contentType: string | null } | undefined;
    server.use(
      http.post(URL, async ({ request }) => {
        received = {
          body: await request.json(),
          csrf: request.headers.get('X-XSRF-TOKEN'),
          contentType: request.headers.get('Content-Type'),
        };
        return HttpResponse.json(eligible);
      }),
    );
    renderPanel();

    await userEvent.type(screen.getByLabelText('Amount (GBP)'), '25.50');
    await userEvent.click(screen.getByRole('button', { name: 'Check eligibility' }));

    expect(await screen.findByText('Eligible')).toBeInTheDocument();
    expect(received).toEqual({
      body: { paymentRef: 'PAY-000001', amount: '25.50', currency: 'GBP' },
      csrf: 'csrf-from-cookie',
      contentType: 'application/json',
    });
    const reasons = screen.getByRole('list', { name: 'Eligibility reasons' });
    expect(within(reasons).getByText('WITHIN_POLICY')).toBeInTheDocument();
    expect(screen.getByText('SYNTHETIC-REFUND-POLICY-2026.11-v1')).toBeInTheDocument();
    expect(screen.getByText(/no refund was created/)).toBeInTheDocument();
  });

  it('checks the full remaining amount when no amount is entered', async () => {
    let body: unknown;
    server.use(
      http.post(URL, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          ...eligible,
          decision: 'MANUAL_REVIEW',
          reasons: [{ code: 'SUPERVISOR_LIMIT_EXCEEDED', outcome: 'MANUAL_REVIEW', message: 'Needs escalation.' }],
        });
      }),
    );
    renderPanel();

    await userEvent.click(screen.getByRole('button', { name: 'Check eligibility' }));

    expect(await screen.findByText('Manual review')).toBeInTheDocument();
    expect(body).toEqual({ paymentRef: 'PAY-000001' });
    expect(screen.getByText('SUPERVISOR_LIMIT_EXCEEDED')).toBeInTheDocument();
  });

  it('rejects malformed amounts without calling the API', async () => {
    let calls = 0;
    server.use(
      http.post(URL, () => {
        calls += 1;
        return HttpResponse.json(eligible);
      }),
    );
    renderPanel();

    await userEvent.type(screen.getByLabelText('Amount (GBP)'), '12,50');
    await userEvent.click(screen.getByRole('button', { name: 'Check eligibility' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Enter an amount such as 25 or 25.50/);
    expect(calls).toBe(0);
  });

  it('shows the business rule code when the server rejects the precision', async () => {
    server.use(
      http.post(URL, () =>
        HttpResponse.json(
          { title: 'Business rule violated', status: 422, detail: 'GBP amounts allow at most 2 decimal places', code: 'INVALID_AMOUNT_SCALE' },
          { status: 422 },
        ),
      ),
    );
    renderPanel();

    await userEvent.type(screen.getByLabelText('Amount (GBP)'), '10.001');
    await userEvent.click(screen.getByRole('button', { name: 'Check eligibility' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'GBP amounts allow at most 2 decimal places (INVALID_AMOUNT_SCALE)',
    );
  });

  it('explains a permission error and shows a loading state while checking', async () => {
    server.use(
      http.post(URL, async () => {
        await delay(50);
        return HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 });
      }),
    );
    renderPanel();

    await userEvent.click(screen.getByRole('button', { name: 'Check eligibility' }));
    expect(screen.getByRole('button', { name: 'Checking…' })).toBeDisabled();
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to check refund eligibility.');
  });
});
