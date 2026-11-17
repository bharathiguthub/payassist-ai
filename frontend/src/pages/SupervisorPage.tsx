import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { PaymentStats } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';

export function SupervisorPage() {
  const { data, isPending, error } = useQuery({
    queryKey: ['payment-stats'],
    queryFn: () => apiFetch<PaymentStats>('/api/v1/payments/stats'),
  });

  return (
    <section>
      <h1>Supervisor overview</h1>
      {isPending && <p>Loading…</p>}
      {error && (
        <p className="error" role="alert">
          Could not load statistics: {error.message}
        </p>
      )}
      {data && (
        <div className="card">
          <p className="muted">{data.totalPayments} payments in total</p>
          <table>
            <thead>
              <tr>
                <th>Status</th>
                <th className="num">Payments</th>
                <th className="num">Share</th>
              </tr>
            </thead>
            <tbody>
              {data.byStatus.map((s) => (
                <tr key={s.status}>
                  <td>
                    <StatusBadge status={s.status} />
                  </td>
                  <td className="num">{s.count}</td>
                  <td className="num">
                    {data.totalPayments ? ((s.count / data.totalPayments) * 100).toFixed(1) : '0.0'}%
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
