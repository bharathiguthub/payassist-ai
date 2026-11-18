import { expect, test } from '@playwright/test';
import { api, signIn, USERS } from './support';

/** Real Keycloak login, role-based navigation, server-side denials and browser-side token hygiene. */
test.describe('Sign-in, roles and session security', () => {
  const navFor = {
    [USERS.alice]: ['Payments', 'Disputes'],
    [USERS.sam]: ['Payments', 'Supervisor', 'Refund approvals', 'Disputes', 'Dispute review'],
    [USERS.ada]: ['Payments', 'Supervisor', 'Refund approvals', 'Disputes', 'Dispute review', 'Audit log'],
  };

  for (const [username, links] of Object.entries(navFor)) {
    test(`${username} sees exactly the navigation for their role`, async ({ browser }) => {
      const session = await signIn(browser, username);
      const nav = session.page.getByRole('navigation', { name: 'Main' });
      await expect(nav.getByRole('link')).toHaveText(links);
      await session.context.close();
    });
  }

  test('a user without a PayAssist role gets the no-access page and 403s from the API', async ({ browser }) => {
    const session = await signIn(browser, USERS.nora);
    await expect(session.page.getByRole('heading', { name: 'No access' })).toBeVisible();
    expect((await api(session, 'GET', '/api/v1/payments')).status()).toBe(403);
    await session.context.close();
  });

  test('direct URLs to pages above the role show Access denied, and the backend refuses the data', async ({ browser }) => {
    const session = await signIn(browser, USERS.alice);
    for (const path of ['/admin/audit', '/refunds/approvals', '/supervisor']) {
      await session.page.goto(path);
      await expect(session.page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    }
    expect((await api(session, 'GET', '/api/v1/admin/audit-events')).status()).toBe(403);
    expect((await api(session, 'GET', '/api/v1/admin/audit-events/checkpoint-status')).status()).toBe(403);
    expect((await api(session, 'GET', '/api/v1/payments/stats')).status()).toBe(403);
    await session.context.close();
  });

  test('tokens never reach the browser; the session cookie is HttpOnly; writes need the CSRF token', async ({ browser }) => {
    const session = await signIn(browser, USERS.alice);
    await session.page.goto('/payments/PAY-000042');
    await expect(session.page.getByRole('heading', { name: 'Payment PAY-000042' })).toBeVisible();

    const storage = await session.page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(storage).not.toMatch(/eyJ|token/i);
    expect(await session.page.evaluate(() => document.cookie)).not.toContain('PAYASSIST_SESSION');
    expect(await session.page.content()).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
    const cookie = (await session.context.cookies()).find((c) => c.name === 'PAYASSIST_SESSION');
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Lax');

    const withoutCsrf = await session.page.request.post('/api/v1/refunds/eligibility-check', {
      data: { paymentRef: 'PAY-000042' },
      headers: { Accept: 'application/json' },
    });
    expect(withoutCsrf.status()).toBe(403);
    await session.context.close();
  });

  test('signing out ends the PayAssist session', async ({ browser }) => {
    const session = await signIn(browser, USERS.alice);
    await session.page.getByRole('button', { name: 'Sign out' }).click();
    await session.page.waitForURL((url) => url.host === 'localhost:3000');
    await expect(session.page.getByRole('button', { name: 'Sign in with company SSO' })).toBeVisible();
    expect((await api(session, 'GET', '/api/v1/payments')).status()).toBe(401);
    await session.context.close();
  });
});
