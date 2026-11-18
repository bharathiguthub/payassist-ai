import { EmptyState, Loading } from './ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { ApiError, apiFetch } from '../api/client';
import type { InvestigationSummary } from '../api/types';
import { formatAmount, formatDateTime, humanize } from '../format';

/** Verified facts and rule-based findings for a payment. No text here is generated or inferred. */
export function InvestigationSummaryCard({ paymentRef }: { paymentRef: string }) {
  const { data, isPending, error } = useQuery({
    queryKey: ['investigation-summary', paymentRef],
    queryFn: () =>
      apiFetch<InvestigationSummary>(`/api/v1/payments/${encodeURIComponent(paymentRef)}/investigation-summary`),
    retry: false,
  });

  return (
    <section className="card" aria-labelledby="summary-heading">
      <h2 id="summary-heading">Investigation summary</h2>
      {isPending && <Loading label="Loading summary" />}
      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError && error.status === 403
            ? 'You do not have permission to view this summary.'
            : `Could not load the summary: ${error.message}`}
        </p>
      )}
      {data && (
        <>
          <p>{data.payment.statusDescription}</p>
          {data.findings.length === 0 ? (
            <EmptyState>No findings: the recorded history shows nothing that needs attention.</EmptyState>
          ) : (
            <ul className="findings" aria-label="Findings">
              {data.findings.map((f) => (
                <li key={f.code}>
                  <span className={`badge ${f.severity === 'WARNING' ? 'badge-warning' : 'badge-info'}`}>
                    {humanize(f.severity)}
                  </span>
                  <span>
                    {f.message}
                    {f.evidence.length > 0 && (
                      <span className="muted small"> (events {f.evidence.map((n) => `#${n}`).join(', ')})</span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <dl className="section-gap">
            <dt>Refunded</dt>
            <dd>
              {formatAmount(data.money.refundedAmount, data.money.currency)} of{' '}
              {formatAmount(data.money.paymentAmount, data.money.currency)} ({data.money.refundCount} refund
              {data.money.refundCount === 1 ? '' : 's'})
            </dd>
            <dt>Dispute</dt>
            <dd>
              {humanize(data.dispute.state)}
              {data.dispute.reasonCode ? ` (${data.dispute.reasonCode})` : ''}
              {data.disputeCase && (
                <>
                  {' · case '}
                  <Link to={`/disputes/${data.disputeCase.caseRef}`}>{data.disputeCase.caseRef}</Link>
                  {` (${humanize(data.disputeCase.status)}${data.disputeCase.overdue ? ', overdue' : ''})`}
                </>
              )}
            </dd>
            <dt>History</dt>
            <dd>
              {data.history.eventCount} events
              {data.history.complete ? '' : ' (incomplete: recorded from migration onwards)'}
            </dd>
            <dt>Possible next events</dt>
            <dd>{data.possibleNextEvents.length ? data.possibleNextEvents.map(humanize).join(', ') : 'None (final)'}</dd>
          </dl>
          <p className="muted small section-gap">
            {data.basis} Generated {formatDateTime(data.generatedAt)}.
          </p>
        </>
      )}
    </section>
  );
}
