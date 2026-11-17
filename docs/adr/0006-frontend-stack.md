# ADR 0006 — Frontend stack

**Status:** Accepted (Phase 1)

## Decision
- **React 19 + TypeScript (strict, `noUncheckedIndexedAccess`) + Vite.**
- **React Router 7** for routing; **TanStack Query** for server state (caching, retries, and
  re-checking `/me` when any call returns 401, which happens when a session expires).
- **No OIDC library in the browser.** Login is a full-page navigation to `/oauth2/authorization/keycloak`;
  logout is a form POST. All protocol handling stays in the BFF.
- Plain CSS with design tokens (light/dark). No UI framework yet, to keep the dependency surface small.
- Tests: **Vitest + Testing Library + MSW**, so components are tested against realistic HTTP behaviour.
- Served in Docker by **nginx-unprivileged**, which also reverse-proxies BFF paths and sets the CSP and
  other security headers.

## Consequences
- The chat/agent UI (later phases) will likely need streaming (SSE). The single-origin proxy setup
  already supports it, but nginx will need `proxy_buffering off` for those routes.
- `frontend/.npmrc` sets `legacy-peer-deps=true` to work around an npm 11.3 resolver crash on vitest's
  optional peer dependencies.
