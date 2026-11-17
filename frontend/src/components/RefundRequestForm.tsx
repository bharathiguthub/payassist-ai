import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { ApiError, apiFetch } from '../api/client';
import { describeApiError } from '../api/problems';
import { REFUND_REASONS, type CreateRefundRequestBody, type RefundReason, type RefundRequestView } from '../api/types';
import { formatAmount, humanize } from '../format';
import { RefundStatusBadge } from './RefundStatusBadge';

const AMOUNT_FORMAT = /^\d{1,15}(\.\d{1,4})?$/;

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `k-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Submits a refund request for supervisor approval. Nothing is refunded until a supervisor approves.
 *
 * Idempotency: one key per payload. Retrying the same payload (e.g. after a network error) reuses the key,
 * so the server returns the original request instead of creating a duplicate; changing the payload, or a
 * successful submission, starts a new key.
 */
export function RefundRequestForm({ paymentRef, currency }: { paymentRef: string; currency: string }) {
  const queryClient = useQueryClient();
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState<RefundReason>('CUSTOMER_REQUEST');
  const [formError, setFormError] = useState<string | null>(null);
  const attempt = useRef<{ payload: string; key: string } | null>(null);

  const submit = useMutation({
    mutationFn: ({ body, key }: { body: CreateRefundRequestBody; key: string }) =>
      apiFetch<RefundRequestView>('/api/v1/refunds', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      attempt.current = null;
      setAmount('');
      void queryClient.invalidateQueries({ queryKey: ['refunds', paymentRef] });
    },
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = amount.trim();
    if (!AMOUNT_FORMAT.test(trimmed)) {
      setFormError('Enter the amount to refund, such as 25 or 25.50.');
      return;
    }
    setFormError(null);
    const body: CreateRefundRequestBody = { paymentRef, amount: trimmed, currency, reason };
    const payload = JSON.stringify(body);
    if (attempt.current?.payload !== payload) attempt.current = { payload, key: newKey() };
    submit.mutate({ body, key: attempt.current.key });
  }

  const created = submit.data;

  return (
    <section className="card" aria-labelledby="refund-request-heading">
      <h2 id="refund-request-heading">Request a refund</h2>
      <form className="search" onSubmit={onSubmit} noValidate>
        <label>
          Refund amount ({currency})
          <input
            name="refundAmount"
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <label>
          Reason
          <select value={reason} onChange={(e) => setReason(e.target.value as RefundReason)}>
            {REFUND_REASONS.map((r) => (
              <option key={r} value={r}>
                {humanize(r)}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={submit.isPending}>
          {submit.isPending ? 'Submitting…' : 'Submit for approval'}
        </button>
        {formError && (
          <p className="error" role="alert">
            {formError}
          </p>
        )}
      </form>
      {submit.isError && (
        <div className="error section-gap" role="alert">
          <p>Refund request not submitted: {describeApiError(submit.error, 'You do not have permission to request refunds.')}</p>
          {submit.error instanceof ApiError && submit.error.problem?.reasons && (
            <ul aria-label="Refusal reasons">
              {submit.error.problem.reasons.map((r) => (
                <li key={r}>
                  <code>{r}</code>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {created && !submit.isError && (
        <p className="section-gap" role="status">
          Request <strong>{created.refundRef}</strong> for {formatAmount(created.amount, created.currency)} submitted:{' '}
          <RefundStatusBadge status={created.status} />. No money moves until a supervisor approves it.
        </p>
      )}
    </section>
  );
}
