import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { AuditEvent, AuditIntegrityReport } from '../api/types';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { AuditPage } from './AuditPage';

const chained: AuditEvent = {
  id: 201,
  occurredAt: '2026-11-17T10:00:00Z',
  actor: 'agent.alice',
  actorType: 'USER',
  action: 'LOGIN_SUCCESS',
  resourceType: 'AUTHENTICATION',
  resourceId: 'oidc:keycloak',
  correlationId: 'cid-12345678',
  outcome: 'SUCCESS',
  details: { roles: 'CARE_AGENT' },
  protection: 'CHAINED',
  chainSeq: 12,
  rowHash: 'a'.repeat(64),
};

const legacy: AuditEvent = {
  ...chained,
  id: 3,
  action: 'VIEW',
  resourceType: 'PAYMENT',
  resourceId: 'PAY-000042',
  outcome: 'SUCCESS',
  details: {},
  protection: 'LEGACY',
  chainSeq: null,
  rowHash: null,
};

function list(capture: URL[], events: AuditEvent[] = [chained, legacy]) {
  server.use(
    http.get('/api/v1/admin/audit-events', ({ request }) => {
      capture.push(new URL(request.url));
      return HttpResponse.json({ content: events, page: 0, size: 50, totalElements: events.length, totalPages: 1 });
    }),
  );
}

function integrity(report: Partial<AuditIntegrityReport>) {
  server.use(
    http.get('/api/v1/admin/audit-events/integrity', () =>
      HttpResponse.json({
        result: 'VALID',
        checkedAt: '2026-11-17T10:05:00Z',
        legacyRows: 164,
        chainedRows: 52,
        headChainSeq: 52,
        headRowHash: 'b'.repeat(64),
        firstFailure: null,
        ...report,
      }),
    ),
  );
}

describe('Audit log page', () => {
  it('distinguishes chained from legacy rows and shows details as text', async () => {
    list([], [chained, { ...legacy, details: { note: '<img src=x onerror=alert(1)>' } }]);
    renderWithProviders(<AuditPage />);

    const table = await screen.findByRole('table', { name: 'Audit events' });
    const rows = within(table).getAllByRole('row');
    expect(within(rows[1]!).getByText('Chained #12')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('roles=CARE_AGENT')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('Legacy')).toBeInTheDocument();
    expect(within(rows[2]!).getByText('note=<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(table.querySelector('img')).toBeNull();
  });

  it('sends validated filters to the API', async () => {
    const urls: URL[] = [];
    list(urls);
    renderWithProviders(<AuditPage />);
    await screen.findByRole('table', { name: 'Audit events' });

    await userEvent.type(screen.getByLabelText('Username'), 'agent.alice');
    await userEvent.type(screen.getByLabelText('Action'), 'login_success');
    await userEvent.selectOptions(screen.getByLabelText('Outcome'), 'DENIED');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));

    await vi.waitFor(() => {
      const last = urls.at(-1)!;
      expect(last.searchParams.get('actor')).toBe('agent.alice');
      expect(last.searchParams.get('action')).toBe('LOGIN_SUCCESS');
      expect(last.searchParams.get('outcome')).toBe('DENIED');
    });
  });

  it('refuses a malformed filter without calling the API', async () => {
    const urls: URL[] = [];
    list(urls);
    renderWithProviders(<AuditPage />);
    await screen.findByRole('table', { name: 'Audit events' });
    const before = urls.length;

    await userEvent.type(screen.getByLabelText('Username'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/usernames contain only/i);
    expect(urls.length).toBe(before);
  });

  it('reports an intact chain with its head as a checkpoint', async () => {
    list([]);
    integrity({});
    renderWithProviders(<AuditPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Chain intact');
    expect(status).toHaveTextContent('52 chained and 164 legacy rows');
    expect(status).toHaveTextContent('Head #52');
  });

  it('reports a broken chain with the first failing event', async () => {
    list([]);
    integrity({ result: 'BROKEN', firstFailure: { auditEventId: 77, chainSeq: 9, reason: 'ROW_HASH_MISMATCH' } });
    renderWithProviders(<AuditPage />);

    await userEvent.click(screen.getByRole('button', { name: 'Verify integrity' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Chain broken');
    expect(alert).toHaveTextContent('ROW_HASH_MISMATCH at audit event 77');
  });
});
