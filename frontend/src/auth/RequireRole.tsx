import type { ReactNode } from 'react';
import type { Role } from '../api/types';
import { hasAnyRole, useCurrentUser } from './auth';

/**
 * Hides UI the user's roles do not allow. This is a usability feature only: the backend enforces
 * every rule independently, so bypassing this component yields 403s, not data.
 */
export function RequireRole({ roles, children }: { roles: readonly Role[]; children: ReactNode }) {
  const { data: user } = useCurrentUser();
  if (!hasAnyRole(user, roles)) {
    return (
      <section className="card" role="alert">
        <h2>Access denied</h2>
        <p>Your account does not have permission to view this page.</p>
      </section>
    );
  }
  return <>{children}</>;
}
