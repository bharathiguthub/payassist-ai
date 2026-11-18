import type { CurrentUser } from '../api/types';
import { logout } from '../auth/auth';

export function NoAccessPage({ user }: { user: CurrentUser }) {
  return (
    <main className="login">
      <section className="card login-card" role="alert">
        <h1>No access</h1>
        <p>
          You are signed in as <strong>{user.username}</strong>, but your account has no PayAssist role.
          Ask an administrator to grant CARE_AGENT, CARE_SUPERVISOR or ADMIN.
        </p>
        <button type="button" className="secondary" onClick={logout}>
          Sign out
        </button>
      </section>
    </main>
  );
}
