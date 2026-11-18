import { Loading } from '../components/ui';
import { useState, type FormEvent } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { apiFetch, toQuery } from '../api/client';
import { PAYMENT_STATUSES, type Page, type PaymentListItem } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { formatAmount, formatDateTime, humanize } from '../format';

const PAGE_SIZE = 20;
const QUERY_PATTERN = /^[A-Za-z0-9-]*$/;

export function PaymentSearchPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const status = params.get('status') ?? '';
  const page = Number(params.get('page') ?? '0');

  const [draftQ, setDraftQ] = useState(q);
  const [draftStatus, setDraftStatus] = useState(status);
  const [inputError, setInputError] = useState<string | null>(null);

  const { data, isPending, isError, error } = useQuery({
    queryKey: ['payments', q, status, page],
    queryFn: () =>
      apiFetch<Page<PaymentListItem>>(`/api/v1/payments${toQuery({ q, status, page, size: PAGE_SIZE })}`),
    placeholderData: keepPreviousData,
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = draftQ.trim();
    if (!QUERY_PATTERN.test(trimmed)) {
      setInputError('Use letters, digits and hyphens only, e.g. PAY-000123 or CUS-00012.');
      return;
    }
    setInputError(null);
    const next: Record<string, string> = {};
    if (trimmed) next.q = trimmed;
    if (draftStatus) next.status = draftStatus;
    setParams(next);
  }

  function goToPage(nextPage: number) {
    const next = new URLSearchParams(params);
    next.set('page', String(nextPage));
    setParams(next);
  }

  return (
    <section>
      <h1>Payment investigation</h1>
      <form className="card search" onSubmit={onSubmit} role="search">
        <label>
          Payment or customer reference
          <input
            name="q"
            value={draftQ}
            maxLength={32}
            placeholder="PAY-000123 or CUS-00012"
            onChange={(e) => setDraftQ(e.target.value)}
          />
        </label>
        <label>
          Status
          <select name="status" value={draftStatus} onChange={(e) => setDraftStatus(e.target.value)}>
            <option value="">Any status</option>
            {PAYMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {humanize(s)}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Search</button>
        {inputError && (
          <p className="error" role="alert">
            {inputError}
          </p>
        )}
      </form>

      {isPending && <Loading label="Loading payments" />}
      {isError && (
        <p className="error" role="alert">
          Could not load payments: {error.message}
        </p>
      )}
      {data && (
        <div className="card">
          <p className="muted">
            {data.totalElements} payment{data.totalElements === 1 ? '' : 's'} found
          </p>
          {data.content.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Payment</th>
                    <th>Customer</th>
                    <th>Merchant</th>
                    <th className="num">Amount</th>
                    <th>Method</th>
                    <th>Status</th>
                    <th>Created</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((p) => (
                    <tr key={p.paymentRef}>
                      <td>
                        <Link to={`/payments/${p.paymentRef}`}>{p.paymentRef}</Link>
                      </td>
                      <td>
                        {p.customerName}
                        <small className="muted block">{p.customerRef}</small>
                      </td>
                      <td>{p.merchantName}</td>
                      <td className="num">{formatAmount(p.amount, p.currency)}</td>
                      <td>{humanize(p.paymentMethod)}</td>
                      <td>
                        <StatusBadge status={p.status} />
                      </td>
                      <td>{formatDateTime(p.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.totalPages > 1 && (
            <nav className="pager" aria-label="Pagination">
              <button type="button" className="secondary" disabled={page <= 0} onClick={() => goToPage(page - 1)}>
                Previous
              </button>
              <span>
                Page {data.page + 1} of {data.totalPages}
              </span>
              <button
                type="button"
                className="secondary"
                disabled={page + 1 >= data.totalPages}
                onClick={() => goToPage(page + 1)}
              >
                Next
              </button>
            </nav>
          )}
        </div>
      )}
    </section>
  );
}
