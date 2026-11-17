import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { apiFetch } from '../api/client';
import { describeApiError } from '../api/problems';
import { DISPUTE_STATUSES, type DisputeStatus, type DisputeSummary, type Page } from '../api/types';
import { DisputeStatusBadge, OverdueBadge } from '../components/DisputeStatusBadge';
import { formatAmount, formatDateTime, humanize } from '../format';

interface Filters {
  statuses: DisputeStatus[];
  paymentRef: string;
  overdue: boolean;
  mine: boolean;
}

function toUrl(f: Filters, page: number): string {
  const params = new URLSearchParams();
  f.statuses.forEach((s) => params.append('status', s));
  if (f.paymentRef) params.set('paymentRef', f.paymentRef);
  if (f.overdue) params.set('overdue', 'true');
  if (f.mine) params.set('mine', 'true');
  params.set('page', String(page));
  params.set('size', '20');
  return `/api/v1/disputes?${params.toString()}`;
}

/**
 * Dispute case search. Reused as the supervisor review queue with preset statuses. Sorted by response deadline
 * (as provided by the bank or network), then newest.
 */
export function DisputesPage({
  title = 'Disputes',
  presetStatuses = [],
  intro,
}: {
  title?: string;
  presetStatuses?: DisputeStatus[];
  intro?: string;
}) {
  const [filters, setFilters] = useState<Filters>({ statuses: presetStatuses, paymentRef: '', overdue: false, mine: false });
  const [draft, setDraft] = useState({ status: presetStatuses.length === 1 ? presetStatuses[0] : '', paymentRef: '' });
  const [formError, setFormError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const { data, isPending, error } = useQuery({
    queryKey: ['disputes', filters, page],
    queryFn: () => apiFetch<Page<DisputeSummary>>(toUrl(filters, page)),
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const ref = draft.paymentRef.trim();
    if (ref && !/^[A-Za-z0-9-]{1,32}$/.test(ref)) {
      setFormError('Payment references contain only letters, digits and hyphens.');
      return;
    }
    setFormError(null);
    setPage(0);
    setFilters({
      ...filters,
      statuses: draft.status ? [draft.status as DisputeStatus] : presetStatuses,
      paymentRef: ref.toUpperCase(),
    });
  }

  return (
    <section>
      <h1>{title}</h1>
      {intro && <p className="muted">{intro}</p>}
      <form className="search card" onSubmit={onSubmit} noValidate>
        <label>
          Payment reference
          <input value={draft.paymentRef} onChange={(e) => setDraft({ ...draft, paymentRef: e.target.value })} />
        </label>
        <label>
          Case status
          <select value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>
            <option value="">{presetStatuses.length ? 'Queue default' : 'All'}</option>
            {DISPUTE_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </select>
        </label>
        <label className="inline">
          <input
            type="checkbox"
            checked={filters.overdue}
            onChange={(e) => {
              setPage(0);
              setFilters({ ...filters, overdue: e.target.checked });
            }}
          />
          Overdue only
        </label>
        <label className="inline">
          <input
            type="checkbox"
            checked={filters.mine}
            onChange={(e) => {
              setPage(0);
              setFilters({ ...filters, mine: e.target.checked });
            }}
          />
          Opened by me
        </label>
        <button type="submit">Search</button>
        {formError && (
          <p className="error" role="alert">
            {formError}
          </p>
        )}
      </form>

      {isPending && <p>Loading dispute cases…</p>}
      {error && (
        <p className="error" role="alert">
          Could not load dispute cases: {describeApiError(error)}
        </p>
      )}
      {data && data.content.length === 0 && <p className="muted">No dispute cases match.</p>}
      {data && data.content.length > 0 && (
        <div className="card table-wrap">
          <table aria-label="Dispute cases">
            <thead>
              <tr>
                <th>Case</th>
                <th>Payment</th>
                <th>Category</th>
                <th className="num">Disputed</th>
                <th>Status</th>
                <th>Response due (as provided)</th>
                <th>Opened</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((d) => (
                <tr key={d.caseRef}>
                  <td>
                    <Link to={`/disputes/${d.caseRef}`}>{d.caseRef}</Link>
                    {d.source === 'CARD_NETWORK' && <span className="block muted small">From card network</span>}
                  </td>
                  <td>
                    <Link to={`/payments/${d.paymentRef}`}>{d.paymentRef}</Link>
                  </td>
                  <td>{humanize(d.reasonCategory)}</td>
                  <td className="num">{formatAmount(d.disputedAmount, d.currency)}</td>
                  <td>
                    <DisputeStatusBadge status={d.status} />
                  </td>
                  <td>
                    {d.responseDueAt ? formatDateTime(d.responseDueAt) : <span className="muted">Not provided</span>}{' '}
                    <OverdueBadge overdue={d.overdue} />
                  </td>
                  <td>
                    {d.openedBy}
                    <span className="block muted small">{formatDateTime(d.openedAt)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span className="small muted">
              Page {data.page + 1} of {Math.max(data.totalPages, 1)} · {data.totalElements} cases
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

export function DisputeReviewPage() {
  return (
    <DisputesPage
      title="Dispute review"
      presetStatuses={['PENDING_REVIEW', 'EVIDENCE_SUBMITTED']}
      intro="Cases waiting for a supervisor: approve or return evidence packages, and record outcomes reported by the bank or network. You cannot approve a review you requested yourself."
    />
  );
}
