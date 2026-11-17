import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { AuditEvent, Page } from '../api/types';
import { formatDateTime } from '../format';

export function AuditPage() {
  const { data, isPending, error } = useQuery({
    queryKey: ['audit-events'],
    queryFn: () => apiFetch<Page<AuditEvent>>('/api/v1/admin/audit-events?size=50'),
  });

  return (
    <section>
      <h1>Audit log</h1>
      {isPending && <p>Loading…</p>}
      {error && (
        <p className="error" role="alert">
          Could not load audit events: {error.message}
        </p>
      )}
      {data && (
        <div className="card">
          {data.content.length === 0 ? (
            <p className="muted">No audit events yet.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Who</th>
                  <th>Action</th>
                  <th>Resource</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((e) => (
                  <tr key={e.id}>
                    <td>{formatDateTime(e.occurredAt)}</td>
                    <td>{e.actor}</td>
                    <td>{e.action}</td>
                    <td>
                      {e.resourceType} {e.resourceId}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}
