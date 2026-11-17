import { NavLink, Outlet } from 'react-router';
import type { CurrentUser } from '../api/types';
import { hasAnyRole, logout } from '../auth/auth';
import { humanize } from '../format';

export function Layout({ user }: { user: CurrentUser }) {
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          PayAssist <span>AI</span>
        </div>
        <nav aria-label="Main">
          <NavLink to="/payments">Payments</NavLink>
          {hasAnyRole(user, ['CARE_SUPERVISOR', 'ADMIN']) && <NavLink to="/supervisor">Supervisor</NavLink>}
          {hasAnyRole(user, ['CARE_SUPERVISOR', 'ADMIN']) && <NavLink to="/refunds/approvals">Refund approvals</NavLink>}
          <NavLink to="/disputes" end>
            Disputes
          </NavLink>
          {hasAnyRole(user, ['CARE_SUPERVISOR', 'ADMIN']) && <NavLink to="/disputes/review">Dispute review</NavLink>}
          {hasAnyRole(user, ['ADMIN']) && <NavLink to="/admin/audit">Audit log</NavLink>}
        </nav>
        <div className="user">
          <div>
            <strong>{user.displayName ?? user.username}</strong>
            <small>{user.roles.map(humanize).join(', ')}</small>
          </div>
          <button type="button" className="secondary" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>
      <main>
        <Outlet />
      </main>
      <footer className="footer">Synthetic data only &middot; no real money moves</footer>
    </div>
  );
}
