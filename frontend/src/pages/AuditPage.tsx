import { useMutation, useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { apiFetch } from '../api/client';
import { describeApiError } from '../api/problems';
import type { AuditEvent, AuditIntegrityReport, Page } from '../api/types';
import { formatDateTime } from '../format';

interface Filters {
  actor: string;
  action: string;
  resourceId: string;
  outcome: string;
}

const EMPTY: Filters = { actor: '', action: '', resourceId: '', outcome: '' };

function toUrl(f: Filters, page: number): string {
  const params = new URLSearchParams();
  if (f.actor) params.set('actor', f.actor);
  if (f.action) params.set('action', f.action);
  if (f.resourceId) params.set('resourceId', f.resourceId);
  if (f.outcome) params.set('outcome', f.outcome);
  params.set('page', String(page));
  params.set('size', '50');
  return `/api/v1/admin/audit-events?${params.toString()}`;
}

function validate(f: Filters): string | null {
  if (f.actor && !/^[A-Za-z0-9._:-]{1,128}$/.test(f.actor)) return 'Usernames contain only letters, digits and . _ : -';
  if (f.action && !/^[A-Z][A-Z_]{0,63}$/.test(f.action)) return 'Actions are upper-case codes such as LOGIN_SUCCESS.';
  if (f.resourceId && !/^[A-Za-z0-9 ._:/-]{1,128}$/.test(f.resourceId)) return 'Resource IDs are references such as PAY-000042.';
  return null;
}

const OUTCOME_BADGE: Record<AuditEvent['outcome'], string> = {
  SUCCESS: 'badge-success',
  DENIED: 'badge-danger',
  FAILED: 'badge-warning',
};

/**
 * ADMIN-only audit trail. Every search and every integrity check is itself recorded by the backend
 * (AUDIT_LOG_VIEW / AUDIT_INTEGRITY_CHECK). Values are rendered as text only.
 */
export function AuditPage() {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [formError, setFormError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const { data, isPending, error } = useQuery({
    queryKey: ['audit-events', filters, page],
    queryFn: () => apiFetch<Page<AuditEvent>>(toUrl(filters, page)),
  });

  const integrity = useMutation({
    mutationFn: () => apiFetch<AuditIntegrityReport>('/api/v1/admin/audit-events/integrity'),
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const next = {
      actor: draft.actor.trim(),
      action: draft.action.trim().toUpperCase(),
      resourceId: draft.resourceId.trim(),
      outcome: draft.outcome,
    };
    const problem = validate(next);
    setFormError(problem);
    if (problem) return;
    setPage(0);
    setFilters(next);
  }

  return (
    <section>
      <h1>Audit log</h1>
      <p className="muted">
        Every search and integrity check on this page is itself recorded in the audit log.
      </p>

      <div className="card">
        <button type="button" onClick={() => integrity.mutate()} disabled={integrity.isPending}>
          {integrity.isPending ? 'Verifying…' : 'Verify integrity'}
        </button>
        {integrity.error && (
          <p className="error" role="alert">
            Could not verify the audit trail: {describeApiError(integrity.error)}
          </p>
        )}
        {integrity.data && <IntegrityResult report={integrity.data} />}
      </div>

      <form className="search card" onSubmit={onSubmit} noValidate>
        <label>
          Username
          <input value={draft.actor} onChange={(e) => setDraft({ ...draft, actor: e.target.value })} />
        </label>
        <label>
          Action
          <input value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })} />
        </label>
        <label>
          Resource ID
          <input value={draft.resourceId} onChange={(e) => setDraft({ ...draft, resourceId: e.target.value })} />
        </label>
        <label>
          Outcome
          <select value={draft.outcome} onChange={(e) => setDraft({ ...draft, outcome: e.target.value })}>
            <option value="">All</option>
            <option value="SUCCESS">Success</option>
            <option value="DENIED">Denied</option>
            <option value="FAILED">Failed</option>
          </select>
        </label>
        <button type="submit">Search</button>
        {formError && (
          <p className="error" role="alert">
            {formError}
          </p>
        )}
      </form>

      {isPending && <p>Loading…</p>}
      {error && (
        <p className="error" role="alert">
          Could not load audit events: {describeApiError(error)}
        </p>
      )}
      {data && (
        <div className="card table-wrap">
          {data.content.length === 0 ? (
            <p className="muted">No audit events match.</p>
          ) : (
            <table aria-label="Audit events">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Action</th>
                  <th>Resource</th>
                  <th>Outcome</th>
                  <th>Details</th>
                  <th>Protection</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((e) => (
                  <tr key={e.id}>
                    <td>{formatDateTime(e.occurredAt)}</td>
                    <td>
                      {e.actor}
                      {e.actorType === 'SYSTEM' && <span className="block muted small">System</span>}
                    </td>
                    <td>{e.action}</td>
                    <td>
                      {e.resourceType} {e.resourceId}
                    </td>
                    <td>
                      <span className={`badge ${OUTCOME_BADGE[e.outcome]}`}>{e.outcome}</span>
                    </td>
                    <td className="small">
                      {Object.entries(e.details ?? {})
                        .map(([k, v]) => `${k}=${v}`)
                        .join(', ')}
                    </td>
                    <td>
                      {e.protection === 'CHAINED' ? (
                        <span className="badge badge-info" title={e.rowHash ?? undefined}>
                          Chained #{e.chainSeq}
                        </span>
                      ) : (
                        <span className="badge badge-neutral" title="Written before the hash chain; pinned by the genesis anchor">
                          Legacy
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="pager">
            <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span className="small muted">
              Page {data.page + 1} of {Math.max(data.totalPages, 1)} · {data.totalElements} events
            </span>
            <button
              type="button"
              className="secondary"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function IntegrityResult({ report }: { report: AuditIntegrityReport }) {
  if (report.result === 'VALID') {
    return (
      <p role="status">
        <span className="badge badge-success">Chain intact</span> {report.chainedRows} chained and {report.legacyRows}{' '}
        legacy rows verified at {formatDateTime(report.checkedAt)}. Head #{report.headChainSeq}{' '}
        <code title={report.headRowHash ?? undefined}>{report.headRowHash?.slice(0, 16)}…</code>
        <span className="block muted small">
          Keep the head number and hash elsewhere: removal of the newest rows is only detectable against such a
          checkpoint.
        </span>
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
