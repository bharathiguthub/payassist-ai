import { EmptyState, Loading } from './ui';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch } from '../api/client';
import type { PaymentTimeline as Timeline, TimelineEntry } from '../api/types';
import { formatAmount, formatDateTime, humanize } from '../format';

function kindClass(entry: TimelineEntry): string {
  if (entry.kind === 'INFORMATIONAL') return 'informational';
  if (entry.kind === 'SYSTEM_ONLY') return 'system';
  return 'lifecycle';
}

/** Chronological, recorded history of a payment. Every line is a recorded fact from the backend. */
export function PaymentTimeline({ paymentRef }: { paymentRef: string }) {
  const { data, isPending, error } = useQuery({
    queryKey: ['payment-timeline', paymentRef],
    queryFn: () => apiFetch<Timeline>(`/api/v1/payments/${encodeURIComponent(paymentRef)}/timeline`),
    retry: false,
  });

  return (
    <section className="card" aria-labelledby="timeline-heading">
      <h2 id="timeline-heading">Timeline</h2>
      {isPending && <Loading label="Loading timeline" />}
      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError && error.status === 403
            ? 'You do not have permission to view this timeline.'
            : `Could not load the timeline: ${error.message}`}
        </p>
      )}
      {data && data.events.length === 0 && <EmptyState>No events have been recorded for this payment.</EmptyState>}
      {data && data.events.length > 0 && (
        <ol className="timeline" aria-label={`Events for ${data.paymentRef}, oldest first`}>
          {data.events.map((e) => (
            <li key={e.sequenceNo} className={kindClass(e)}>
              <div className="timeline-head">
                <strong>{humanize(e.eventType)}</strong>
                <time dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time>
                {e.amount !== null && e.currency && <span>{formatAmount(e.amount, e.currency)}</span>}
                {e.recordedOutOfOrder && <span className="badge badge-neutral">Recorded late</span>}
              </div>
              <div>{e.description}</div>
              {e.reasonCode && (
                <div className="small">
                  Reason {e.reasonCode}
                  {e.reasonDescription ? `: ${e.reasonDescription}` : ''}
                </div>
              )}
              <div className="timeline-meta">
                #{e.sequenceNo} · {humanize(e.source)} · {e.actor}
                {e.correlationId ? ` · correlation ${e.correlationId}` : ''}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
