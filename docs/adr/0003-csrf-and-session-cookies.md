# ADR 0003 — CSRF protection and session cookies

**Status:** Accepted (Phase 1)

## Context
With cookie-based sessions, a malicious site could make the browser send authenticated requests (CSRF).

## Decision
- Spring Security CSRF protection stays **enabled** for every unsafe method, including `/logout`.
- Tokens use the **double-submit cookie** pattern: `CookieCsrfTokenRepository.withHttpOnlyFalse()`
  issues `XSRF-TOKEN`. The SPA echoes it in the `X-XSRF-TOKEN` header (fetch), or in the `_csrf`
  field (logout form, which must be a top-level navigation to follow redirects to Keycloak).
- `CsrfTokenRequestAttributeHandler` with the request attribute name set to `null`: the token is
  loaded on every request, so the cookie is always present, and the raw value is accepted. XOR
  masking (BREACH mitigation) is not used because the token is never rendered into a response body.
- The session cookie is `SameSite=Lax`. `Strict` would drop the cookie on the redirect back from
  Keycloak and break login. Lax still blocks cross-site POSTs.
- Session fixation protection: the session ID changes at login.

## Consequences
- Every future mutating endpoint (refund requests, chat messages) is protected automatically. The SPA
  client (`apiFetch`) already adds the header for non-GET requests.
- Tests verify: a missing token gives 403, a forged token gives 403, and a valid header or form token
  is accepted (`SecurityIT`, `scripts/smoke-test.ps1`).
