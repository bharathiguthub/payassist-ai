import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { apiFetch } from '../api/client';
import { useIdempotencyKey } from '../api/idempotency';
import { describeApiError } from '../api/problems';
import { DISPUTE_REASON_CATEGORIES, type DisputeReasonCategory, type DisputeView } from '../api/types';
import { humanize } from '../format';

/**
 * Opens a dispute case for a bank / card-network / wallet-provider dispute. The response deadline is optional and is
 * entered exactly as given in the notification (interpreted as UTC); PayAssist never computes one.
 */
export function OpenDisputeForm({ paymentRef, onOpened }: { paymentRef: string; onOpened?: () => void }) {
  const queryClient = useQueryClient();
  const idempotency = useIdempotencyKey();
  const [category, setCategory] = useState<DisputeReasonCategory>('PRODUCT_NOT_RECEIVED');
  const [networkCode, setNetworkCode] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const open = useMutation({
    mutationFn: ({ body, key }: { body: object; key: string }) =>
      apiFetch<DisputeView>('/api/v1/disputes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      idempotency.reset();
      onOpened?.(); // lets the page keep this confirmation visible after the payment becomes DISPUTED
      void queryClient.invalidateQueries({ queryKey: ['payment', paymentRef] });
      void queryClient.invalidateQueries({ queryKey: ['investigation-summary', paymentRef] });
      void queryClient.invalidateQueries({ queryKey: ['payment-timeline', paymentRef] });
    },
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const code = networkCode.trim();
    if (code && !/^[A-Za-z0-9._-]{1,16}$/.test(code)) {
      setFormError('Network reason codes are up to 16 letters, digits, dots, underscores or hyphens.');
      return;
    }
    setFormError(null);
    const body = {
      paymentRef,
      reasonCategory: category,
      networkReasonCode: code || undefined,
      responseDueAt: dueAt ? `${dueAt}:00Z` : undefined,
    };
    open.mutate({ body, key: idempotency.keyFor(JSON.stringify(body)) });
  }

  return (
    <section className="card" aria-labelledby="open-dispute-heading">
      <h2 id="open-dispute-heading">Open a dispute case</h2>
      <p className="muted small">
        For a dispute the customer raised with their bank, card network or wallet provider. The case covers the full
        unrefunded amount, blocks refunds while it is active, and does not reverse the payment.
      </p>
      {open.data ? (
        <p role="status">
          Case <Link to={`/disputes/${open.data.caseRef}`}>{open.data.caseRef}</Link> opened.
        </p>
      ) : (
        <form className="search" onSubmit={onSubmit} noValidate>
          <label>
            Category
            <select value={category} onChange={(e) => setCategory(e.target.value as DisputeReasonCategory)}>
              {DISPUTE_REASON_CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {humanize(c)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Network reason code (optional, as provided)
            <input value={networkCode} onChange={(e) => setNetworkCode(e.target.value)} />
          </label>
          <label>
            Response due, UTC (optional, as provided)
            <input type="datetime-local" value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
          </label>
          <button type="submit" disabled={open.isPending}>
            {open.isPending ? 'Opening…' : 'Open dispute case'}
          </button>
          {formError && (
            <p className="error" role="alert">
              {formError}
            </p>
          )}
        </form>
      )}
      {open.isError && (
        <p className="error" role="alert">
          Case not opened: {describeApiError(open.error, 'You do not have permission to open dispute cases.')}
        </p>
      )}
    </section>
  );
}
