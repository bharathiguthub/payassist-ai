import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { ApiError, apiFetch } from '../api/client';
import type { EligibilityCheckRequest, EligibilityDecision, RefundEligibility } from '../api/types';
import { formatAmount, formatDateTime, humanize } from '../format';

const DECISION_TONE: Record<EligibilityDecision, string> = {
  ELIGIBLE: 'badge-success',
  MANUAL_REVIEW: 'badge-warning',
  INELIGIBLE: 'badge-danger',
};

/** Digits with an optional fraction. Precision per currency is decided by the server (never rounded). */
const AMOUNT_FORMAT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Advisory refund eligibility check. Read-only: it never creates or reserves a refund. The decision,
 * reason codes and policy version come from the server's rules engine; nothing is computed here.
 */
export function RefundEligibilityPanel({ paymentRef, currency }: { paymentRef: string; currency: string }) {
  const [amount, setAmount] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const check = useMutation({
    mutationFn: (body: EligibilityCheckRequest) =>
      apiFetch<RefundEligibility>('/api/v1/refunds/eligibility-check', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }),
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = amount.trim();
    if (trimmed !== '' && !AMOUNT_FORMAT.test(trimmed)) {
      setFormError('Enter an amount such as 25 or 25.50, or leave it empty to check the full remaining amount.');
      return;
    }
    setFormError(null);
    check.mutate(trimmed === '' ? { paymentRef } : { paymentRef, amount: trimmed, currency });
  }

  const result = check.data;
  const error = check.error;

  return (
    <section className="card" aria-labelledby="eligibility-heading">
      <h2 id="eligibility-heading">Refund eligibility</h2>
      <form className="search" onSubmit={onSubmit} noValidate>
        <label>
          Amount ({currency})
          <input
            name="amount"
            inputMode="decimal"
            autoComplete="off"
            placeholder="Full remaining amount"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
        </label>
        <button type="submit" disabled={check.isPending}>
          {check.isPending ? 'Checking…' : 'Check eligibility'}
        </button>
        {formError && (
          <p className="error" role="alert">
            {formError}
          </p>
        )}
      </form>

      {error && (
        <p className="error section-gap" role="alert">
          {error instanceof ApiError && error.status === 403
            ? 'You do not have permission to check refund eligibility.'
            : error instanceof ApiError && error.problem?.code
              ? `${error.problem.detail ?? error.message} (${error.problem.code})`
              : `Could not check eligibility: ${error.message}`}
        </p>
      )}

      {result && !error && (
        <div className="section-gap" aria-live="polite">
          <p>
            <span className={`badge ${DECISION_TONE[result.decision]}`}>{humanize(result.decision)}</span>{' '}
            for {formatAmount(result.requestedAmount, result.currency)}
          </p>
          <ul className="findings" aria-label="Eligibility reasons">
            {result.reasons.map((r) => (
              <li key={r.code}>
                <code className="small">{r.code}</code>
                <span>{r.message}</span>
              </li>
            ))}
          </ul>
          <dl className="section-gap">
            <dt>Remaining refundable</dt>
            <dd>{formatAmount(result.remainingRefundableAmount, result.paymentCurrency)}</dd>
            <dt>Already refunded</dt>
            <dd>{formatAmount(result.previouslyRefundedAmount, result.paymentCurrency)}</dd>
            <dt>Pending approval</dt>
            <dd>{formatAmount(result.reservedPendingAmount, result.paymentCurrency)}</dd>
            <dt>Refund window</dt>
            <dd>
              {result.refundWindow.days} days, {result.refundWindow.open ? 'open until' : 'closed on'}{' '}
              {formatDateTime(result.refundWindow.endsAt)}
            </dd>
            <dt>Policy</dt>
            <dd>{result.policyVersion}</dd>
          </dl>
          <p className="muted small section-gap">{result.advisory}</p>
        </div>
      )}
    </section>
  );
}
