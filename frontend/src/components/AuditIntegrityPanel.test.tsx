import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { axe } from '../test/axe';
import { renderWithProviders } from '../test/render';
import { healthyCheckpointStatus, server } from '../test/server';
import { AuditIntegrityPanel } from './AuditIntegrityPanel';

const URL = '/api/v1/admin/audit-events/checkpoint-status';

describe('AuditIntegrityPanel', () => {
  it('shows the database chain and the reported external checkpoints as separate guarantees', async () => {
    const { container } = renderWithProviders(<AuditIntegrityPanel />);

    const db = await screen.findByRole('region', { name: 'Database audit chain' });
    const ext = screen.getByRole('region', { name: 'External checkpoints' });
    expect(await within(db).findByText('Valid')).toBeInTheDocument();
    expect(within(db).getByText('#240')).toBeInTheDocument();
    expect(within(ext).getByText('reported')).toBeInTheDocument();
    expect(within(ext).getByText(/#233/)).toHaveTextContent('7 audit rows since');
    expect(screen.getByText('No integrity warnings.')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\bmac\b|KEY|\/checkpoints\//);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('lists danger warnings first and shows unverified and failed states', async () => {
    server.use(
      http.get(URL, () =>
        HttpResponse.json({
          ...healthyCheckpointStatus,
          databaseChain: { currentHeadChainSeq: 12, lastVerifiedAt: null, lastResult: null },
          externalCheckpoints: {
            lastRunAt: '2026-11-17T09:00:00Z',
            lastRunMode: 'VERIFY',
            lastRunResult: 'FAILED',
            lastRunReason: 'CHECKPOINT_MISMATCH',
            lastSuccessfulRunAt: null,
            checkpoints: 2,
            latestCheckpointHead: 10,
            latestCheckpointAt: '2026-11-15T09:00:00Z',
            rowsSinceLatestCheckpoint: 2,
          },
          warnings: [
            {
              code: 'LAST_RUN_FAILED',
              severity: 'DANGER',
              message: 'The last external checkpoint run failed (CHECKPOINT_MISMATCH).',
            },
            { code: 'DB_CHAIN_NOT_VERIFIED', severity: 'WARNING', message: 'Not verified recently.' },
          ],
        }),
      ),
    );
    renderWithProviders(<AuditIntegrityPanel />);

    const items = within(await screen.findByRole('list', { name: 'Integrity warnings' })).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('Action required: The last external checkpoint run failed');
    expect(items[1]).toHaveTextContent('Warning: Not verified recently.');
    const db = screen.getByRole('region', { name: 'Database audit chain' });
    expect(within(db).getByText('Not verified')).toBeInTheDocument();
    const ext = screen.getByRole('region', { name: 'External checkpoints' });
    expect(within(ext).getByText('Failed')).toBeInTheDocument();
  });

  it('verifies the database chain on demand and refreshes the status', async () => {
    let statusCalls = 0;
    server.use(
      http.get(URL, () => {
        statusCalls++;
        return HttpResponse.json(healthyCheckpointStatus);
      }),
      http.get('/api/v1/admin/audit-events/integrity', () =>
        HttpResponse.json({
          result: 'VALID',
          checkedAt: '2026-11-17T12:00:00Z',
          legacyRows: 164,
          chainedRows: 241,
          headChainSeq: 241,
          headRowHash: 'c'.repeat(64),
          firstFailure: null,
        }),
      ),
    );
    renderWithProviders(<AuditIntegrityPanel />);
    await userEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }));
    expect(await screen.findByText('Chain intact')).toBeInTheDocument();
    await vi.waitFor(() => expect(statusCalls).toBe(2));
  });
});
