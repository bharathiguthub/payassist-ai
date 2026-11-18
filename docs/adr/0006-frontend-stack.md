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

## Update — Phase 2 milestone 7 (frontend polish)
- **One error model.** `api/problems.ts` classifies every failure (`unauthenticated`, `forbidden`, `not-permitted`,
  `not-found`, `stale`, `protected`, `invalid`, `network`, `server`); `<ProblemMessage>` shows plain language, the
  stable code as secondary text, a Reload or Sign-in action where it helps, and the correlation ID for server and
  database refusals. Server text for 5xx is never shown. A 401 from a query **or a mutation** re-checks `/me`.
- **The backend stays the authority.** Buttons follow the server's `allowedActions`; final refund and dispute states
  are shown read-only with who decided and when; any refusal reloads the data.
- **Confirmation dialogs** (`<ConfirmDialog>`, `role="alertdialog"`, focus kept inside, Escape cancels, focus
  returns to the opener) for final decisions: refund approve/reject, dispute evidence approval, outcome, liability,
  withdrawal.
- **Accessibility baseline:** skip link, page heading focused after navigation, `aria-current` navigation, status
  regions for loading, one `<Loading>`/`<EmptyState>` pattern, visible focus, reduced-motion support. `vitest-axe`
  checks pages in jsdom; the browser suite runs axe-core in Chromium (including colour contrast).
- **Browser tests:** Playwright (Chromium only) against the running Docker stack with real Keycloak logins
  (`npm run e2e`), separate from `npm test`. No traces or videos (they would record the typed password); the password
  is read from `.env` at run time; no session is saved to disk.
