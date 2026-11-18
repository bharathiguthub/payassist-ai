import { expect, test } from '@playwright/test';
import { api, disputablePayment, signIn, USERS } from './support';

/**
 * Dispute case management through the UI: an agent opens and works a case, a supervisor approves the evidence after
 * confirming, another supervisor records WON after confirming, and the closed case is final and read-only. Uses a
 * payment from the reserved dispute pool; WON restores it to SETTLED, so the next run can reuse it.
 */
test.describe.serial('Dispute management', () => {
  let paymentRef = '';
  let caseRef = '';

  test('an agent opens a case, is stopped from saving a card number, adds evidence and requests review', async ({
    browser,
  }) => {
    const alice = await signIn(browser, USERS.alice);
    paymentRef = await disputablePayment(alice);
    const page = alice.page;
    await page.goto(`/payments/${paymentRef}`);

    const form = page.getByRole('region', { name: 'Open a dispute case' });
    await form.getByLabel('Category').selectOption('PRODUCT_NOT_RECEIVED');
    await form.getByRole('button', { name: 'Open dispute case' }).click();
    const link = form.getByRole('status').getByRole('link');
    caseRef = (await link.textContent()) ?? '';
    expect(caseRef).toMatch(/^DSP-/);
    await link.click();
    await expect(page.getByRole('heading', { level: 1, name: new RegExp(caseRef) })).toBeVisible();

    await page.getByRole('button', { name: 'Start investigation' }).click();
    await expect(page.getByText('Case is now under investigation.')).toBeVisible();

    await page.getByLabel(/^Add a note/).fill('Customer card 4111 1111 1111 1111 mentioned on the call');
    await page.getByRole('button', { name: 'Add note' }).click();
    const refusal = page.getByRole('alert');
    await expect(refusal).toContainText('looks like it contains a card number');
    await expect(refusal).toContainText('CARD_NUMBER_DETECTED');

    await page.getByLabel(/^Add a note/).fill('Customer confirms the parcel never arrived; courier ticket requested.');
    await page.getByRole('button', { name: 'Add note' }).click();
    await expect(page.getByText('Note added.')).toBeVisible();

    await page.getByLabel(/^Document reference/).fill('E2E-COURIER-TICKET-1');
    await page.getByRole('button', { name: 'Add evidence' }).click();
    await expect(page.getByText('Evidence added.')).toBeVisible();

    await page.getByRole('button', { name: 'Request supervisor review' }).click();
    await expect(page.getByText('Case is now pending review.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve evidence submission' })).toHaveCount(0);
    await alice.context.close();
  });

  test('a supervisor approves the evidence submission after confirming', async ({ browser }) => {
    const sam = await signIn(browser, USERS.sam);
    await sam.page.goto(`/disputes/${caseRef}`);
    await sam.page.getByRole('button', { name: 'Approve evidence submission' }).click();
    const dialog = sam.page.getByRole('alertdialog');
    await expect(dialog).toContainText(`Approve the evidence submission for ${caseRef}?`);
    await dialog.getByRole('button', { name: 'Approve submission' }).click();
    await expect(sam.page.getByText('Case is now evidence submitted.')).toBeVisible();
    await sam.context.close();
  });

  test('another supervisor records WON; the case is closed, final and the payment restored', async ({ browser }) => {
    const tara = await signIn(browser, USERS.tara);
    const page = tara.page;
    await page.goto(`/disputes/${caseRef}`);
    await page.getByLabel('Outcome reported by the bank or network').selectOption('WON');
    await page.getByRole('button', { name: 'Record outcome' }).click();
    const dialog = page.getByRole('alertdialog', { name: `Record outcome Won for ${caseRef}?` });
    await expect(dialog).toContainText('restored');
    await dialog.getByRole('button', { name: 'Record outcome' }).click();

    await expect(page.getByRole('note').filter({ hasText: 'This case is closed (Won) and final.' })).toBeVisible();
    await expect(page.getByText('No actions: the case is closed.')).toBeVisible();

    const payment = await (await api(tara, 'GET', `/api/v1/payments/${paymentRef}`)).json();
    expect(payment.status).toBe('SETTLED');
    const reopen = await api(tara, 'POST', `/api/v1/disputes/${caseRef}/withdraw`, {
      reason: 'RAISED_IN_ERROR',
      expectedVersion: 99,
    });
    expect([409, 422]).toContain(reopen.status());
    await tara.context.close();
  });
});
