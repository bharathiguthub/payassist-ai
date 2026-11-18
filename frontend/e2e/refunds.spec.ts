import { expect, test } from '@playwright/test';
import { api, refundablePayment, signIn, USERS } from './support';

/**
 * Payment investigation, refund request, supervisor approval and final state, through the UI with real logins.
 * Approves a 1.00 refund on a payment of the reserved refund pool (simulated: no money moves).
 */
test.describe.serial('Investigation, refund request and supervisor approval', () => {
  let paymentRef = '';
  let refundRef = '';
  let samRefundRef = '';

  test('an agent investigates a payment: search, details, timeline and summary', async ({ browser }) => {
    const alice = await signIn(browser, USERS.alice);
    const page = alice.page;
    await page.getByLabel('Payment or customer reference').fill('PAY-000042');
    await page.getByRole('button', { name: 'Search' }).click();
    await page.getByRole('link', { name: 'PAY-000042' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Payment PAY-000042' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Timeline' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /Investigation summary/ })).toBeVisible();
    await alice.context.close();
  });

  test('an admin investigates read-only: no refund or dispute forms', async ({ browser }) => {
    const ada = await signIn(browser, USERS.ada);
    await ada.page.goto('/payments/PAY-000042');
    await expect(ada.page.getByRole('heading', { name: /Investigation summary/ })).toBeVisible();
    await expect(ada.page.getByRole('heading', { name: 'Request a refund' })).toHaveCount(0);
    await expect(ada.page.getByRole('heading', { name: 'Open a dispute case' })).toHaveCount(0);
    await ada.context.close();
  });

  test('an agent checks eligibility and requests a 1.00 refund', async ({ browser }) => {
    const alice = await signIn(browser, USERS.alice);
    paymentRef = await refundablePayment(alice);
    const page = alice.page;
    await page.goto(`/payments/${paymentRef}`);

    const eligibility = page.getByRole('region', { name: 'Refund eligibility' });
    await eligibility.getByLabel(/^Amount/).fill('1.00');
    await eligibility.getByRole('button', { name: 'Check eligibility' }).click();
    await expect(eligibility.getByText(/Eligible/).first()).toBeVisible();

    const form = page.getByRole('region', { name: 'Request a refund' });
    await form.getByLabel(/^Refund amount/).fill('1.00');
    await form.getByRole('button', { name: 'Submit for approval' }).click();
    const status = form.getByRole('status');
    await expect(status).toContainText('submitted');
    refundRef = (await status.locator('strong').textContent()) ?? '';
    expect(refundRef).toMatch(/^RFD-/);
    await alice.context.close();
  });

  test('agents have no approval page; a supervisor cannot decide their own request', async ({ browser }) => {
    const alice = await signIn(browser, USERS.alice);
    await alice.page.goto('/refunds/approvals');
    await expect(alice.page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    expect((await api(alice, 'POST', `/api/v1/refunds/${refundRef}/approve`, {})).status()).toBe(403);
    await alice.context.close();

    const sam = await signIn(browser, USERS.sam);
    const created = await api(sam, 'POST', '/api/v1/refunds',
      { paymentRef, amount: '1.00', currency: (await (await api(sam, 'GET', `/api/v1/payments/${paymentRef}`)).json()).currency, reason: 'GOODWILL' },
      { 'Idempotency-Key': `e2e-${Date.now()}` });
    expect(created.status()).toBe(201);
    samRefundRef = (await created.json()).refundRef;

    await sam.page.goto('/refunds/approvals');
    const row = sam.page.getByRole('row', { name: new RegExp(samRefundRef) });
    await expect(row.getByText('No action available')).toBeVisible();
    const self = await api(sam, 'POST', `/api/v1/refunds/${samRefundRef}/approve`, {});
    expect(self.status()).toBe(403);
    expect((await self.json()).code).toBe('SELF_APPROVAL_FORBIDDEN');
    await sam.context.close();
  });

  test('another supervisor approves after confirming; the decision is final', async ({ browser }) => {
    const tara = await signIn(browser, USERS.tara);
    const page = tara.page;
    await page.goto('/refunds/approvals');

    await page.getByRole('button', { name: `Approve ${refundRef}` }).click();
    const dialog = page.getByRole('alertdialog', { name: `Approve ${refundRef}?` });
    await expect(dialog).toContainText('cannot be undone');
    await dialog.getByRole('button', { name: 'Approve refund' }).click();
    await expect(page.getByText(`${refundRef} approved.`)).toBeVisible();
    await expect(page.getByRole('row', { name: new RegExp(refundRef) })).toHaveCount(0);

    // Clean up the supervisor's own request: rejected by someone else, its reservation released.
    await page.getByRole('button', { name: `Reject ${samRefundRef}` }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Reject refund' }).click();
    await expect(page.getByText(`${samRefundRef} rejected.`)).toBeVisible();

    const again = await api(tara, 'POST', `/api/v1/refunds/${refundRef}/approve`, {});
    expect(again.status()).toBe(409);

    await page.goto(`/payments/${paymentRef}`);
    const row = page.getByRole('region', { name: 'Refund requests' }).getByRole('row', { name: new RegExp(refundRef) });
    await expect(row).toContainText('Final');
    await expect(row).toContainText('tara.supervisor');
    await tara.context.close();
  });
});
