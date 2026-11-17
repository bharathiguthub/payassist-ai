import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { ApiError, apiFetch } from '../api/client';
import { useIdempotencyKey } from '../api/idempotency';
import { describeApiError } from '../api/problems';
import {
  EVIDENCE_TYPES,
  RETURN_REASONS,
  WITHDRAWAL_REASONS,
  type DisputeView,
  type EvidenceType,
} from '../api/types';
import { DisputeStatusBadge, OverdueBadge } from '../components/DisputeStatusBadge';
import { formatAmount, formatDateTime, humanize } from '../format';

type Transition =
  | { path: 'start-investigation' | 'request-review' | 'approve-submission' | 'accept-liability' }
  | { path: 'return-for-investigation'; reason: string }
  | { path: 'record-outcome'; outcome: 'WON' | 'LOST' }
  | { path: 'withdraw'; reason: string };

function post<T>(url: string, body: unknown, idempotencyKey?: string) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  return apiFetch<T>(url, { method: 'POST', headers, body: JSON.stringify(body) });
}

/**
 * Dispute case investigation. Facts, pending refunds, actions, notes, evidence and history. Every button shown comes
 * from the server's allowedActions for the current user, and the server re-checks every action. Notes are plain text
 * and are rendered as text (React escapes them); they are never interpreted as HTML.
 */
export function DisputeCasePage() {
  const { caseRef = '' } = useParams();
  const queryClient = useQueryClient();
  const key = ['dispute', caseRef];
  const { data: d, isPending, error } = useQuery({
    queryKey: key,
    queryFn: () => apiFetch<DisputeView>(`/api/v1/disputes/${encodeURIComponent(caseRef)}`),
    retry: false,
  });

  const [message, setMessage] = useState<string | null>(null);
  const [returnReason, setReturnReason] = useState<string>(RETURN_REASONS[0]);
  const [withdrawReason, setWithdrawReason] = useState<string>(WITHDRAWAL_REASONS[0]);
  const [outcome, setOutcome] = useState<'WON' | 'LOST'>('WON');
  const [noteText, setNoteText] = useState('');
  const [evidence, setEvidence] = useState<{ type: EvidenceType; documentReference: string; description: string }>({
    type: 'PROOF_OF_DELIVERY',
    documentReference: '',
    description: '',
  });
  const noteKey = useIdempotencyKey();
  const evidenceKey = useIdempotencyKey();

  const onSuccess = (updated: DisputeView, text: string) => {
    queryClient.setQueryData(key, updated);
    void queryClient.invalidateQueries({ queryKey: ['disputes'] });
    setMessage(text);
  };

  const transition = useMutation({
    mutationFn: (t: Transition) => {
      const { path, ...extra } = t;
      return post<DisputeView>(`/api/v1/disputes/${encodeURIComponent(caseRef)}/${path}`, {
        ...extra,
        expectedVersion: d?.version,
      });
    },
    onSuccess: (updated) => onSuccess(updated, `Case is now ${humanize(updated.status).toLowerCase()}.`),
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  const addNote = useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: string; idempotencyKey: string }) =>
      post<DisputeView>(`/api/v1/disputes/${encodeURIComponent(caseRef)}/notes`, { body }, idempotencyKey),
    onSuccess: (updated) => {
      noteKey.reset();
      setNoteText('');
      onSuccess(updated, 'Note added.');
    },
  });

  const addEvidence = useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: object; idempotencyKey: string }) =>
      post<DisputeView>(`/api/v1/disputes/${encodeURIComponent(caseRef)}/evidence`, body, idempotencyKey),
    onSuccess: (updated) => {
      evidenceKey.reset();
      setEvidence({ ...evidence, documentReference: '', description: '' });
      onSuccess(updated, 'Evidence added.');
    },
  });

  function submitNote(event: FormEvent) {
    event.preventDefault();
    const body = noteText.trim();
    if (!body) return;
    addNote.mutate({ body, idempotencyKey: noteKey.keyFor(body) });
  }

  function submitEvidence(event: FormEvent) {
    event.preventDefault();
    const body = {
      type: evidence.type,
      documentReference: evidence.documentReference.trim(),
      description: evidence.description.trim() || undefined,
    };
    addEvidence.mutate({ body, idempotencyKey: evidenceKey.keyFor(JSON.stringify(body)) });
  }

  if (isPending) return <p>Loading dispute case…</p>;
  if (error || !d) {
    return (
      <section>
        <Link to="/disputes">&larr; Back to disputes</Link>
        <p className="error" role="alert">
          {error instanceof ApiError && error.status === 404 ? 'Dispute case not found.' : describeApiError(error)}
        </p>
      </section>
    );
  }

  const can = (a: DisputeView['allowedActions'][number]) => d.allowedActions.includes(a);
  const busy = transition.isPending;
  const failure = transition.error ?? addNote.error ?? addEvidence.error;

  return (
    <section>
      <Link to="/disputes">&larr; Back to disputes</Link>
      <h1>
        Dispute {d.caseRef} <DisputeStatusBadge status={d.status} /> <OverdueBadge overdue={d.overdue} />
      </h1>
      <p className="muted small">
        A dispute is an investigation: while it is active the payment is DISPUTED, which does not mean it was reversed
        or charged back. Refunds are blocked until it ends.
      </p>
      {message && !failure && (
        <p className="success" role="status">
          {message}
        </p>
      )}
      {failure && (
        <p className="error" role="alert">
          Not saved: {describeApiError(failure, 'You do not have permission to do this.')}
        </p>
      )}

      <div className="grid">
        <div className="card">
          <h2>Case</h2>
          <dl>
            <dt>Payment</dt>
            <dd>
              <Link to={`/payments/${d.paymentRef}`}>{d.paymentRef}</Link>
            </dd>
            <dt>Category</dt>
            <dd>
              {humanize(d.reasonCategory)}
              {d.networkReasonCode && <span className="muted"> (network code {d.networkReasonCode}, as provided)</span>}
            </dd>
            <dt>Disputed amount</dt>
            <dd>{formatAmount(d.disputedAmount, d.currency)}</dd>
            <dt>Response due</dt>
            <dd>{d.responseDueAt ? `${formatDateTime(d.responseDueAt)} (as provided)` : 'Not provided'}</dd>
            <dt>Opened</dt>
            <dd>
              {d.openedBy}, {formatDateTime(d.openedAt)}
              {d.source === 'CARD_NETWORK' && ' (from card network)'}
            </dd>
            {d.reviewRequestedBy && (
              <>
                <dt>Review requested by</dt>
                <dd>{d.reviewRequestedBy}</dd>
              </>
            )}
          </dl>
          {d.pendingRefunds.length > 0 && (
            <div className="warning-box section-gap" role="note">
              <strong>Pending refund requests on this payment</strong>
              <p className="small">
                They keep their reservation but cannot be approved while the dispute is active. A supervisor must
                reject them, or approve them after the dispute is won or withdrawn.
              </p>
              <ul>
                {d.pendingRefunds.map((r) => (
                  <li key={r.refundRef}>
                    {r.refundRef}: {formatAmount(r.amount, r.currency)} ({humanize(r.status)})
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <div className="card">
          <h2>Actions</h2>
          {d.allowedActions.length === 0 && <p className="muted">No actions are available to you on this case.</p>}
          <div className="actions">
            {can('START_INVESTIGATION') && (
              <button type="button" disabled={busy} onClick={() => transition.mutate({ path: 'start-investigation' })}>
                Start investigation
              </button>
            )}
            {can('REQUEST_REVIEW') && (
              <button type="button" disabled={busy} onClick={() => transition.mutate({ path: 'request-review' })}>
                Request supervisor review
              </button>
            )}
            {can('APPROVE_SUBMISSION') && (
              <button type="button" disabled={busy} onClick={() => transition.mutate({ path: 'approve-submission' })}>
                Approve evidence submission
              </button>
            )}
            {can('RETURN_FOR_INVESTIGATION') && (
              <span className="inline-actions">
                <select aria-label="Return reason" value={returnReason} onChange={(e) => setReturnReason(e.target.value)}>
                  {RETURN_REASONS.map((r) => (
                    <option key={r} value={r}>
                      {humanize(r)}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => transition.mutate({ path: 'return-for-investigation', reason: returnReason })}
                >
                  Return for investigation
                </button>
              </span>
            )}
            {can('RECORD_OUTCOME') && (
              <span className="inline-actions">
                <select
                  aria-label="Outcome reported by the bank or network"
                  value={outcome}
                  onChange={(e) => setOutcome(e.target.value as 'WON' | 'LOST')}
                >
                  <option value="WON">Won (payment restored)</option>
                  <option value="LOST">Lost (payment charged back)</option>
                </select>
                <button type="button" disabled={busy} onClick={() => transition.mutate({ path: 'record-outcome', outcome })}>
                  Record outcome
                </button>
              </span>
            )}
            {can('ACCEPT_LIABILITY') && (
              <button
                type="button"
                className="secondary"
                disabled={busy}
                onClick={() => transition.mutate({ path: 'accept-liability' })}
              >
                Accept liability (case lost, payment charged back)
              </button>
            )}
            {can('WITHDRAW') && (
              <span className="inline-actions">
                <select
                  aria-label="Withdrawal reason"
                  value={withdrawReason}
                  onChange={(e) => setWithdrawReason(e.target.value)}
                >
                  {WITHDRAWAL_REASONS.map((r) => (
                    <option key={r} value={r}>
                      {humanize(r)}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() => transition.mutate({ path: 'withdraw', reason: withdrawReason })}
                >
                  Record withdrawal
                </button>
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="grid section-gap">
        <section className="card" aria-labelledby="notes-heading">
          <h2 id="notes-heading">Investigation notes</h2>
          {d.notes.length === 0 && <p className="muted">No notes yet.</p>}
          <ol className="notes" aria-label="Notes">
            {d.notes.map((n, i) => (
              <li key={i}>
                <div className="note-body">{n.body}</div>
                <div className="timeline-meta">
                  {n.author} · {formatDateTime(n.createdAt)}
                </div>
              </li>
            ))}
          </ol>
          {can('ADD_NOTE') && (
            <form onSubmit={submitNote} className="stack section-gap">
              <label>
                Add a note (no card numbers or email addresses; notes cannot be edited)
                <textarea maxLength={2000} rows={3} value={noteText} onChange={(e) => setNoteText(e.target.value)} />
              </label>
              <button type="submit" disabled={addNote.isPending || !noteText.trim()}>
                Add note
              </button>
            </form>
          )}
        </section>

        <section className="card" aria-labelledby="evidence-heading">
          <h2 id="evidence-heading">Evidence</h2>
          {d.evidence.length === 0 && <p className="muted">No evidence yet. Review needs at least one item.</p>}
          <ul aria-label="Evidence items">
            {d.evidence.map((e, i) => (
              <li key={i}>
                <strong>{humanize(e.type)}</strong> {e.documentReference}
                {e.description && <span className="block">{e.description}</span>}
                <span className="timeline-meta block">
                  {e.addedBy} · {formatDateTime(e.addedAt)}
                </span>
              </li>
            ))}
          </ul>
          {can('ADD_EVIDENCE') && (
            <form onSubmit={submitEvidence} className="stack section-gap">
              <label>
                Type
                <select
                  value={evidence.type}
                  onChange={(e) => setEvidence({ ...evidence, type: e.target.value as EvidenceType })}
                >
                  {EVIDENCE_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {humanize(t)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Document reference (where the document is stored)
                <input
                  value={evidence.documentReference}
                  onChange={(e) => setEvidence({ ...evidence, documentReference: e.target.value })}
                />
              </label>
              <label>
                Description (optional)
                <input
                  maxLength={500}
                  value={evidence.description}
                  onChange={(e) => setEvidence({ ...evidence, description: e.target.value })}
                />
              </label>
              <button type="submit" disabled={addEvidence.isPending || !evidence.documentReference.trim()}>
                Add evidence
              </button>
            </form>
          )}
        </section>
      </div>

      <section className="card section-gap" aria-labelledby="history-heading">
        <h2 id="history-heading">Case history</h2>
        <div className="table-wrap">
          <table aria-label="Case history">
            <thead>
              <tr>
                <th>#</th>
                <th>Event</th>
                <th>Status</th>
                <th>Reason</th>
                <th>By</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {d.history.map((h) => (
                <tr key={h.sequenceNo}>
                  <td>{h.sequenceNo}</td>
                  <td>
                    {humanize(h.eventType)}
                    {h.paymentLedgerUpdated && <span className="block muted small">Payment ledger updated</span>}
                  </td>
                  <td>
                    {h.fromStatus && h.fromStatus !== h.toStatus ? `${humanize(h.fromStatus)} → ` : ''}
                    {h.toStatus ? humanize(h.toStatus) : ''}
                  </td>
                  <td>{h.reasonCode ? humanize(h.reasonCode) : ''}</td>
                  <td>{h.actor}</td>
                  <td>{formatDateTime(h.occurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  );
}
