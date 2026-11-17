# ADR 0002 — Backend-for-Frontend authentication with Keycloak

**Status:** Accepted (Phase 1)

## Context
Care executives access sensitive payment data. The SPA needs authenticated API access. The OAuth 2.0
for Browser-Based Apps guidance (IETF) recommends a BFF for applications with high security needs,
because any token reachable by JavaScript can be stolen through XSS.

## Decision
- Spring Boot acts as an **OAuth2 confidential client** (`spring-boot-starter-oauth2-client`,
  `oauth2Login`) using the **Authorization Code flow with PKCE (S256)**. PKCE is added via
  `OAuth2AuthorizationRequestCustomizers.withPkce()` and is also **required** on the Keycloak client
  (`pkce.code.challenge.method=S256`).
- Tokens are stored server-side in the HTTP session. The browser holds only `PAYASSIST_SESSION`
  (`HttpOnly`, `SameSite=Lax`, `Secure` unless explicitly disabled for local HTTP).
- The SPA and BFF share **one origin** (nginx in Docker, the Vite proxy in development), so cookies
  are first-party and CORS is not needed.
- The Keycloak client registration is built explicitly rather than through OIDC discovery. The
  front-channel endpoints (authorize, logout) use the browser-facing URL, the back-channel endpoints
  (token, JWKS) use the internal URL, and the expected issuer is pinned. This is required in Docker,
  where the browser and the backend reach Keycloak by different host names.
- `/api/**` returns **401** instead of a login redirect, so the SPA controls the user experience.
  Browser navigations (`Accept: text/html`) are redirected to login.
- Logout is **RP-initiated** (`OidcClientInitiatedLogoutSuccessHandler`), which also ends the Keycloak SSO session.

## Alternatives considered
- **SPA public client with PKCE (tokens in browser memory)** — simpler, no server session; but tokens
  can be stolen by XSS, and refresh-token handling in the browser is fragile.
- **Implicit flow** — deprecated; tokens in URLs.

## Consequences
- The backend is stateful (session). Phase 1 uses in-memory sessions (single instance); scaling out
  requires Spring Session with Redis (planned).
- A session can outlive the access token. Phase 1 does not call downstream APIs with the token, so
  this is acceptable now. When token relay is added (agent service), refresh handling via
  `OAuth2AuthorizedClientManager` becomes mandatory.
- Cookie authentication makes CSRF protection essential (see ADR 0003).
