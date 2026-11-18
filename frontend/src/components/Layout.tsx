import { useEffect } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import type { CurrentUser } from '../api/types';
import { hasAnyRole, logout } from '../auth/auth';
import { humanize } from '../format';

/**
 * App shell. Navigation links are shown per role for convenience only: every page and API call is authorised again
 * by the backend. After each navigation the page heading receives focus so screen readers announce the new page.
 */
export function Layout({ user }: { user: CurrentUser }) {
  const { pathname } = useLocation();

  useEffect(() => {
    const heading = document.querySelector<HTMLElement>('main h1');
    if (heading) {
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
    }
  }, [pathname]);

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
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
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
      <footer className="footer">Synthetic data only &middot; no real money moves</footer>
    </div>
  );
}
