import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { CurrentUser, Page, PaymentListItem, Role } from '../api/types';

export function user(roles: Role[]): CurrentUser {
  return { username: 'agent.alice', displayName: 'Alice Agent', email: 'agent.alice@example.com', roles };
}

export const samplePayments: Page<PaymentListItem> = {
  content: [
    {
      paymentRef: 'PAY-000001',
      customerRef: 'CUS-00002',
      customerName: 'Riley Testwell',
      amount: 84.19,
      currency: 'USD',
      status: 'SETTLED',
      paymentMethod: 'CARD',
      merchantName: 'Demo Travel Co',
      createdAt: '2026-05-18T00:53:00Z',
    },
    {
      paymentRef: 'PAY-000002',
      customerRef: 'CUS-00003',
      customerName: 'Morgan Testwell',
      amount: 163.38,
      currency: 'INR',
      status: 'FAILED',
      paymentMethod: 'BANK_TRANSFER',
      merchantName: 'Sample Groceries Inc',
      createdAt: '2026-10-02T01:46:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
};

/** Default: not logged in. Tests override /api/v1/me per scenario. */
export const server = setupServer(
  http.get('/api/v1/me', () => new HttpResponse(null, { status: 401 })),
  http.get('/api/v1/payments', () => HttpResponse.json(samplePayments)),
  http.get('/api/v1/refunds', () =>
    HttpResponse.json({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
  ),
);

export function loggedInAs(roles: Role[]) {
  server.use(http.get('/api/v1/me', () => HttpResponse.json(user(roles))));
}
