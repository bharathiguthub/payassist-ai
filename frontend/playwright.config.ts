import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Browser tests against the RUNNING Docker stack (http://localhost:3000) with real Keycloak logins (ADR 0006, M7).
 * Prerequisites: `docker compose up -d --wait` and the .env from scripts\new-dev-env.ps1.
 *
 * The demo-user password is read from the repository's git-ignored .env at run time and only placed in this
 * process's environment: it is never written to a file, a report or the console. Each test signs in
 * with a fresh browser context; no login session is saved to disk. Traces and videos are off because a
 * trace would record the typed password; screenshots are kept only for failures.
 *
 * The suite writes SYNTHETIC data to the dev database (refund requests approved for 1.00 on reserved payments, dispute
 * cases taken to WON). See e2e/support.ts for the reserved payments.
 */
function demoPassword(): string {
  if (process.env.PAYASSIST_DEMO_PASSWORD) return process.env.PAYASSIST_DEMO_PASSWORD;
  try {
    // npm run e2e runs from frontend/; the .env lives in the repository root
    const line = readFileSync(resolve(process.cwd(), '..', '.env'), 'utf8')
      .split(/\r?\n/)
      .map((l) => l.trim()) // Compose accepts indented lines
      .find((l) => l.startsWith('PAYASSIST_DEMO_PASSWORD='));
    return line ? line.slice('PAYASSIST_DEMO_PASSWORD='.length).trim() : '';
  } catch {
    return '';
  }
}
process.env.PAYASSIST_DEMO_PASSWORD = demoPassword();

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1, // the specs share one dev database and Keycloak; run them in order
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.PAYASSIST_BASE_URL ?? 'http://localhost:3000',
    trace: 'off',
    video: 'off',
    screenshot: 'only-on-failure',
    locale: 'en-GB',
    timezoneId: 'UTC',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
