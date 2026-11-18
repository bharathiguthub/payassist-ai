import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

/** The synthetic Keycloak personas (realm-payassist.json). */
export const USERS = {
  alice: 'agent.alice', // CARE_AGENT
  sam: 'sam.supervisor', // CARE_SUPERVISOR
  tara: 'tara.supervisor', // CARE_SUPERVISOR
  ada: 'ada.admin', // ADMIN
  nora: 'nora.norole', // no PayAssist role
} as const;

/**
 * Synthetic seed payments RESERVED for the browser suite on the dev database (SETTLED CARD payments with an open
 * refund window). Refund pool: 1.00 refunds are requested and APPROVED (simulated REFUND_RECORDED events; the payment
 * becomes partially refunded and stays eligible for many runs). Dispute pool: never refunded; each run opens a case
 * and records WON, which restores the payment to SETTLED. Other tests and the smoke test do not use these payments.
 */
export const REFUND_POOL = ['PAY-000381', 'PAY-000324'];
export const DISPUTE_POOL = ['PAY-000348', 'PAY-000129'];

export interface Session {
  context: BrowserContext;
  page: Page;
}

/** Real login through Keycloak in a fresh browser context. The password never appears in output. */
export async function signIn(browser: Browser, username: string): Promise<Session> {
  const password = process.env.PAYASSIST_DEMO_PASSWORD;
  if (!password) throw new Error('PAYASSIST_DEMO_PASSWORD not available (.env missing?)');
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/');
  await page.getByRole('button', { name: 'Sign in with company SSO' }).click();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.host === 'localhost:3000'); // back from Keycloak (localhost:8180)
  await expect(page.getByRole('button', { name: 'Sign out' }).or(page.getByRole('heading', { name: 'No access' })).first(),
  ).toBeVisible();
  return { context, page };
}

async function csrf(context: BrowserContext): Promise<string> {
  const cookie = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN');
  return cookie?.value ?? '';
}

/** API call with the browser's session cookie (and CSRF token for writes), like the SPA does. */
export async function api(
  session: Session,
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
) {
  const request = session.page.request;
  if (method === 'GET') return request.get(path, { headers: { Accept: 'application/json', ...headers } });
  return request.post(path, {
    headers: { Accept: 'application/json', 'X-XSRF-TOKEN': await csrf(session.context), ...headers },
    data: body ?? {},
  });
}

/** First payment of a pool that the server says can be refunded 1.00 now. */
export async function refundablePayment(session: Session): Promise<string> {
  for (const ref of REFUND_POOL) {
    const res = await api(session, 'POST', '/api/v1/refunds/eligibility-check', { paymentRef: ref });
    if (res.ok() && (await res.json()).decision === 'ELIGIBLE') return ref;
  }
  throw new Error(`No payment in the reserved refund pool is eligible: ${REFUND_POOL.join(', ')}`);
}

/** First payment of the dispute pool that is SETTLED and has no active case. */
export async function disputablePayment(session: Session): Promise<string> {
  for (const ref of DISPUTE_POOL) {
    const payment = await (await api(session, 'GET', `/api/v1/payments/${ref}`)).json();
    if (payment.status === 'SETTLED') return ref;
  }
  throw new Error(`No payment in the reserved dispute pool is SETTLED: ${DISPUTE_POOL.join(', ')}`);
}

const axeSource = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8');

/** Runs axe-core in the real browser (including colour contrast) and returns the violations. */
export async function axeViolations(page: Page) {
  await page.evaluate(axeSource); // via the DevTools protocol: the app's strict CSP forbids inline scripts
  return page.evaluate(async () => {
    const result = await (window as unknown as { axe: { run: () => Promise<{ violations: { id: string; nodes: unknown[] }[] }> } }).axe.run();
    return result.violations.map((v) => `${v.id} (${v.nodes.length})`);
  });
}
