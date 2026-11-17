import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { apiFetch } from '../api/client';
import { describeApiError } from '../api/problems';
import { REJECTION_REASONS, type Page, type RefundRequestView, type RejectionReason } from '../api/types';
import { RefundStatusBadge } from '../components/RefundStatusBadge';
import { formatAmount, formatDateTime, humanize } from '../format';

const QUEUE_URL = '/api/v1/refunds?status=PENDING_APPROVAL&status=ESCALATION_REQUIRED&size=50';

type Decision =
  | { kind: 'approve'; request: RefundRequestView }
  | { kind: 'reject'; request: RefundRequestView; reason: RejectionReason };

/**
 * Supervisor approval queue. Buttons follow the server's allowedActions for the current user (role,
 * separation of duties, limits); the server re-checks every decision. ADMIN sees the queue read-only.
 */
export function RefundApprovalsPage() {
  const queryClient = useQueryClient();
  const [reasons, setReasons] = useState<Record<string, RejectionReason>>({});
  const [message, setMessage] = useState<string | null>(null);

  const queue = useQuery({ queryKey: ['refund-queue'], queryFn: () => apiFetch<Page<RefundRequestView>>(QUEUE_URL) });

  const decide = useMutation({
    mutationFn: (d: Decision) =>
      apiFetch<RefundRequestView>(`/api/v1/refunds/${encodeURIComponent(d.request.refundRef)}/${d.kind}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(
          d.kind === 'approve'
            ? { expectedVersion: d.request.version }
            : { reason: d.reason, expectedVersion: d.request.version },
        ),
      }),
    onSuccess: (result) => {
      setMessage(`${result.refundRef} ${humanize(result.status).toLowerCase()}.`);
      void queryClient.invalidateQueries({ queryKey: ['refund-queue'] });
    },
    onError: () => {
      setMessage(null);
      void queryClient.invalidateQueries({ queryKey: ['refund-queue'] });
    },
  });

  const rows = queue.data?.content ?? [];

  return (
    <section>
      <h1>Refund approvals</h1>
      <p className="muted">
        Requests awaiting a decision. You cannot decide on your own requests, and requests above the supervisor
        approval limit need escalation. Approving records a simulated refund: no money moves.
      </p>
      {queue.isPending && <p>Loading queue…</p>}
      {queue.error && (
        <p className="error" role="alert">
          Could not load the approval queue: {describeApiError(queue.error)}
        </p>
      )}
      {message && (
        <p className="success" role="status">
          {message}
        </p>
      )}
      {decide.isError && (
        <p className="error" role="alert">
          Decision not recorded: {describeApiError(decide.error)}
        </p>
      )}
      {queue.data && rows.length === 0 && <p className="muted">No refund requests are waiting.</p>}
      {rows.length > 0 && (
        <div className="card table-wrap">
          <table aria-label="Refund approval queue">
            <thead>
              <tr>
                <th>Request</th>
                <th>Payment</th>
                <th className="num">Amount</th>
                <th>Status</th>
                <th>Eligibility</th>
                <th>Requested by</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const canApprove = r.allowedActions.includes('APPROVE');
                const canReject = r.allowedActions.includes('REJECT');
                const reason = reasons[r.refundRef] ?? 'OUTSIDE_POLICY';
                return (
                  <tr key={r.refundRef}>
                    <td>
                      {r.refundRef}
                      <span className="block muted small">{humanize(r.reason)}</span>
                    </td>
                    <td>
                      <Link to={`/payments/${r.paymentRef}`}>{r.paymentRef}</Link>
                    </td>
                    <td className="num">{formatAmount(r.amount, r.currency)}</td>
                    <td>
                      <RefundStatusBadge status={r.status} />
                    </td>
                    <td>
                      {humanize(r.eligibility.decision)}
                      <span className="block muted small">{r.eligibility.reasonCodes.join(', ')}</span>
                    </td>
                    <td>
                      {r.requestedBy}
                      <span className="block muted small">{formatDateTime(r.requestedAt)}</span>
                    </td>
                    <td>
                      {!canApprove && !canReject && <span className="muted small">No action available</span>}
                      {canApprove && (
                        <button
                          type="button"
                          disabled={decide.isPending}
                          onClick={() => decide.mutate({ kind: 'approve', request: r })}
                          aria-label={`Approve ${r.refundRef}`}
                        >
                          Approve
                        </button>
                      )}
                      {canReject && (
                        <span className="inline-actions">
                          <select
                            aria-label={`Rejection reason for ${r.refundRef}`}
                            value={reason}
                            onChange={(e) => setReasons({ ...reasons, [r.refundRef]: e.target.value as RejectionReason })}
                          >
                            {REJECTION_REASONS.map((x) => (
                              <option key={x} value={x}>
                                {humanize(x)}
                              </option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="secondary"
                            disabled={decide.isPending}
                            onClick={() => decide.mutate({ kind: 'reject', request: r, reason })}
                            aria-label={`Reject ${r.refundRef}`}
                          >
                            Reject
                          </button>
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
