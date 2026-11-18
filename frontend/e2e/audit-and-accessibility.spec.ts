import { expect, test } from '@playwright/test';
import { api, axeViolations, signIn, USERS } from './support';

/** Admin audit verification and the integrity panel; axe-core in a real browser (including colour contrast). */
test.describe('Audit verification', () => {
  test('an admin verifies the database chain and sees the external checkpoint status separately', async ({
    browser,
  }) => {
    const ada = await signIn(browser, USERS.ada);
    const page = ada.page;
    await page.goto('/admin/audit');
    await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();

    const db = page.getByRole('region', { name: 'Database audit chain' });
    const ext = page.getByRole('region', { name: 'External checkpoints' });
    await expect(ext).toContainText('reported');
    await expect(ext).toContainText('Newest checkpoint');

    await db.getByRole('button', { name: 'Verify integrity' }).click();
    await expect(db.getByText('Chain intact')).toBeVisible();
    await expect(db.getByText('Valid')).toBeVisible();

    await page.getByLabel('Action').fill('LOGIN_SUCCESS');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page.getByRole('table', { name: 'Audit events' }).getByText('LOGIN_SUCCESS').first()).toBeVisible();

    const body = await page.content();
    expect(body).not.toMatch(/PAYASSIST_AUDIT_CHECKPOINT_KEY|"mac"|audit-checkpoints\.jsonl/);
    await ada.context.close();
  });

  test('supervisors and agents cannot see the audit log or the integrity status', async ({ browser }) => {
    for (const username of [USERS.sam, USERS.alice]) {
      const session = await signIn(browser, username);
      await session.page.goto('/admin/audit');
      await expect(session.page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
      expect((await api(session, 'GET', '/api/v1/admin/audit-events/integrity')).status()).toBe(403);
      await session.context.close();
    }
  });
});

test.describe('Accessibility in the browser (axe-core)', () => {
  const pages: [string, string, string][] = [
    [USERS.alice, '/payments', 'Payment investigation'],
    [USERS.alice, '/payments/PAY-000042', 'Payment PAY-000042'],
    [USERS.sam, '/refunds/approvals', 'Refund approvals'],
    [USERS.sam, '/disputes', 'Disputes'],
    [USERS.ada, '/admin/audit', 'Audit log'],
  ];
  for (const [username, path, heading] of pages) {
    test(`${path} as ${username} has no axe violations`, async ({ browser }) => {
      const session = await signIn(browser, username);
      await session.page.goto(path);
      await expect(session.page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
      await expect(session.page.getByRole('status').filter({ hasText: 'Loading' })).toHaveCount(0);
      expect(await axeViolations(session.page)).toEqual([]);
      await session.context.close();
    });
  }

  test('the login page has no axe violations', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('button', { name: 'Sign in with company SSO' })).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
  });
});
