import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { AuditCheckpointStatus, AuditIntegrityReport } from '../api/types';
import { formatDateTime, humanize } from '../format';
import { Loading, ProblemMessage } from './ui';

const STATUS_URL = '/api/v1/admin/audit-events/checkpoint-status';

/** "3 h ago", "2 d ago": relative to the server's generatedAt, so the browser clock does not matter. */
function age(at: string | null, now: string): string {
  if (!at) return 'never';
  const minutes = Math.max(0, Math.round((Date.parse(now) - Date.parse(at)) / 60000));
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 48 * 60) return `${Math.round(minutes / 60)} h ago`;
  return `${Math.round(minutes / 1440)} d ago`;
}

/**
 * ADMIN-only audit integrity overview (ADR 0013 section 12). Two different guarantees are shown side by side and never
 * merged into one "OK":
 *  - the database audit chain inside PostgreSQL, verified here on demand (Verify integrity);
 *  - the external checkpoints outside PostgreSQL, whose status is only REPORTED by the checkpoint job's runs; the
 *    backend cannot read the ledger, so the proof is re-established only by running the job on the host.
 * Shows no key, MAC, ledger path or full hash.
 */
export function AuditIntegrityPanel() {
  const queryClient = useQueryClient();
  const status = useQuery({
    queryKey: ['audit-checkpoint-status'],
    queryFn: () => apiFetch<AuditCheckpointStatus>(STATUS_URL),
  });
  const verify = useMutation({
    mutationFn: () => apiFetch<AuditIntegrityReport>('/api/v1/admin/audit-events/integrity'),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['audit-checkpoint-status'] }),
  });

  return (
    <section className="card" aria-labelledby="integrity-heading">
      <h2 id="integrity-heading">Audit integrity</h2>
      {status.isPending && <Loading label="Loading integrity status" />}
      {status.error && <ProblemMessage error={status.error} prefix="Could not load the integrity status" />}
      {status.data && status.data.warnings.length > 0 && (
        <ul className="warning-list" aria-label="Integrity warnings">
          {status.data.warnings.map((w) => (
            <li key={w.code} className={w.severity === 'DANGER' ? 'danger' : undefined}>
              <strong>{w.severity === 'DANGER' ? 'Action required' : 'Warning'}:</strong> {w.message}{' '}
              <span className="small">({w.code})</span>
            </li>
          ))}
        </ul>
      )}
      {status.data && status.data.warnings.length === 0 && (
        <p className="success">No integrity warnings.</p>
      )}

      <div className="integrity-grid">
        <section aria-labelledby="db-chain-heading">
          <h3 id="db-chain-heading">Database audit chain</h3>
          <p className="muted small">
            Hash chain inside PostgreSQL. Detects edited or missing rows; cannot detect removal of the newest rows or a
            chain rewritten by someone with database owner rights.
          </p>
          {status.data && (
            <dl>
              <dt>Last verified</dt>
              <dd>
                {status.data.databaseChain.lastVerifiedAt ? (
                  <>
                    {formatDateTime(status.data.databaseChain.lastVerifiedAt)} (
                    {age(status.data.databaseChain.lastVerifiedAt, status.data.generatedAt)})
                  </>
                ) : (
                  'Never'
                )}
              </dd>
              <dt>Last result</dt>
              <dd>
                <ResultBadge result={status.data.databaseChain.lastResult} ok="VALID" />
              </dd>
              <dt>Current head</dt>
              <dd>#{status.data.databaseChain.currentHeadChainSeq ?? '–'}</dd>
            </dl>
          )}
          <button
            type="button"
            className="section-gap"
            onClick={() => verify.mutate()}
            disabled={verify.isPending}
          >
            {verify.isPending ? 'Verifying…' : 'Verify integrity'}
          </button>
          {verify.error && <ProblemMessage error={verify.error} prefix="Could not verify the audit trail" />}
          {verify.data && <IntegrityResult report={verify.data} />}
        </section>

        <section aria-labelledby="checkpoint-heading">
          <h3 id="checkpoint-heading">External checkpoints</h3>
          <p className="muted small">
            HMAC-signed ledger outside PostgreSQL. Status <strong>reported</strong> by the checkpoint job; this page
            cannot verify the ledger itself. To verify now, run <code>.\scripts\audit-checkpoint.ps1</code> on the host
            (add <code>-Create</code> to record a new checkpoint).
          </p>
          {status.data && (
            <dl>
              <dt>Last job run</dt>
              <dd>
                {status.data.externalCheckpoints.lastRunAt ? (
                  <>
                    {formatDateTime(status.data.externalCheckpoints.lastRunAt)} (
                    {age(status.data.externalCheckpoints.lastRunAt, status.data.generatedAt)},{' '}
                    {humanize(status.data.externalCheckpoints.lastRunMode ?? '')})
                  </>
                ) : (
                  'Never'
                )}
              </dd>
              <dt>Reported result</dt>
              <dd>
                <ResultBadge result={status.data.externalCheckpoints.lastRunResult} ok="OK" />
                {status.data.externalCheckpoints.lastRunReason && (
                  <span className="small"> {status.data.externalCheckpoints.lastRunReason}</span>
                )}
              </dd>
              <dt>Last successful verification</dt>
              <dd>{age(status.data.externalCheckpoints.lastSuccessfulRunAt, status.data.generatedAt)}</dd>
              <dt>Newest checkpoint</dt>
              <dd>
                {status.data.externalCheckpoints.latestCheckpointHead != null ? (
                  <>
                    #{status.data.externalCheckpoints.latestCheckpointHead},{' '}
                    {age(status.data.externalCheckpoints.latestCheckpointAt, status.data.generatedAt)} (
                    {status.data.externalCheckpoints.rowsSinceLatestCheckpoint ?? 0} audit rows since)
                  </>
                ) : (
                  'None reported'
                )}
              </dd>
            </dl>
          )}
          {status.data && (
            <p className="muted small">Warnings appear when a checkpoint is older than {status.data.staleAfterHours} hours.</p>
          )}
        </section>
      </div>
    </section>
  );
}

function ResultBadge({ result, ok }: { result: string | null; ok: string }) {
  if (!result) return <span className="badge badge-neutral">Not verified</span>;
  return result === ok ? (
    <span className="badge badge-success">{humanize(result)}</span>
  ) : (
    <span className="badge badge-danger">{humanize(result)}</span>
  );
}

export function IntegrityResult({ report }: { report: AuditIntegrityReport }) {
  if (report.result === 'VALID') {
    return (
      <p role="status">
        <span className="badge badge-success">Chain intact</span> {report.chainedRows} chained and {report.legacyRows}{' '}
        legacy rows verified at {formatDateTime(report.checkedAt)}. Head #{report.headChainSeq}{' '}
        <code title={report.headRowHash ?? undefined}>{report.headRowHash?.slice(0, 16)}…</code>
      </p>
    );
  }
  const failure = report.firstFailure;
  return (
    <p role="alert" className="error">
      <span className="badge badge-danger">Chain broken</span> {failure?.reason}
      {failure?.auditEventId != null && <> at audit event {failure.auditEventId}</>}. Escalate to security; do not
      modify the audit table.
    </p>
  );
}
