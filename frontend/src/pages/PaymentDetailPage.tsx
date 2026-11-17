import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { ApiError, apiFetch } from '../api/client';
import type { PaymentDetails } from '../api/types';
import { InvestigationSummaryCard } from '../components/InvestigationSummaryCard';
import { PaymentTimeline } from '../components/PaymentTimeline';
import { StatusBadge } from '../components/StatusBadge';
import { formatAmount, formatDateTime, humanize } from '../format';

export function PaymentDetailPage() {
  const { paymentRef = '' } = useParams();
  const { data, isPending, error } = useQuery({
    queryKey: ['payment', paymentRef],
    queryFn: () => apiFetch<PaymentDetails>(`/api/v1/payments/${encodeURIComponent(paymentRef)}`),
    retry: false,
  });

  return (
    <section>
      <Link to="/payments">&larr; Back to search</Link>
      <h1>Payment {paymentRef}</h1>
      {isPending && <p>Loading…</p>}
      {error && (
        <p className="error" role="alert">
          {error instanceof ApiError && error.status === 404 ? 'Payment not found.' : `Error: ${error.message}`}
        </p>
      )}
      {data && (
        <div className="grid">
          <div className="card">
            <h2>Payment</h2>
            <dl>
              <dt>Status</dt>
              <dd>
                <StatusBadge status={data.status} />
              </dd>
              <dt>Amount</dt>
              <dd>{formatAmount(data.amount, data.currency)}</dd>
              <dt>Method</dt>
              <dd>{humanize(data.paymentMethod)}</dd>
              <dt>Merchant</dt>
              <dd>{data.merchantName}</dd>
              {data.failureReason && (
                <>
                  <dt>Failure reason</dt>
                  <dd>{humanize(data.failureReason)}</dd>
                </>
              )}
              <dt>Created</dt>
              <dd>{formatDateTime(data.createdAt)}</dd>
              <dt>Last updated</dt>
              <dd>{formatDateTime(data.updatedAt)}</dd>
            </dl>
          </div>
          {data.customer && (
            <div className="card">
              <h2>Customer</h2>
              <dl>
                <dt>Name</dt>
                <dd>{data.customer.fullName}</dd>
                <dt>Reference</dt>
                <dd>{data.customer.customerRef}</dd>
                <dt>Email</dt>
                <dd>{data.customer.maskedEmail}</dd>
                <dt>Country</dt>
                <dd>{data.customer.countryCode}</dd>
              </dl>
            </div>
          )}
        </div>
      )}
      {data && (
        <div className="grid section-gap">
          <InvestigationSummaryCard paymentRef={data.paymentRef} />
          <PaymentTimeline paymentRef={data.paymentRef} />
        </div>
      )}
      {data && <p className="muted small">This view has been recorded in the audit log.</p>}
    </section>
  );
}
