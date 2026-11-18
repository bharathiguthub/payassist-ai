import { EmptyState, Loading, ProblemMessage } from './ui';
import { useQuery } from '@tanstack/react-query';
import { apiFetch, toQuery } from '../api/client';
import type { Page, RefundRequestView } from '../api/types';
import { formatAmount, formatDateTime, humanize } from '../format';
import { RefundStatusBadge } from './RefundStatusBadge';

/** Refund requests for one payment, newest first. */
export function PaymentRefundRequests({ paymentRef }: { paymentRef: string }) {
  const { data, isPending, error } = useQuery({
    queryKey: ['refunds', paymentRef],
    queryFn: () => apiFetch<Page<RefundRequestView>>(`/api/v1/refunds${toQuery({ paymentRef, size: 50 })}`),
    retry: false,
  });

  return (
    <section className="card" aria-labelledby="payment-refunds-heading">
      <h2 id="payment-refunds-heading">Refund requests</h2>
      {isPending && <Loading label="Loading refund requests" />}
      {error && (
        <ProblemMessage error={error} prefix="Could not load refund requests" />
      )}
      {data && data.content.length === 0 && <EmptyState>No refund requests for this payment.</EmptyState>}
      {data && data.content.length > 0 && (
        <div className="table-wrap">
          <table aria-label="Refund requests for this payment">
            <thead>
              <tr>
                <th>Reference</th>
                <th className="num">Amount</th>
                <th>Status</th>
                <th>Requested</th>
                <th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((r) => (
                <tr key={r.refundRef}>
                  <td>{r.refundRef}</td>
                  <td className="num">{formatAmount(r.amount, r.currency)}</td>
                  <td>
                    <RefundStatusBadge status={r.status} />
                  </td>
                  <td>
                    {r.requestedBy}
                    <span className="block muted small">{formatDateTime(r.requestedAt)}</span>
                  </td>
                  <td>
                    {r.decision ? (
                      <>
                        <span className="badge badge-neutral">Final</span> {humanize(r.decision.reasonCode)} by {r.decision.decidedBy}
                        <span className="block muted small">{formatDateTime(r.decision.decidedAt)}</span>
                      </>
                    ) : (
                      <span className="muted">Awaiting supervisor</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
