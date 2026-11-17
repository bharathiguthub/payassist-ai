# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

PayAssist AI: payment customer-support workspace for care executives. **Phase 1 only** is implemented
(OIDC login, RBAC, payment investigation dashboard, audit log, synthetic data). Refund eligibility,
RAG / Spring AI, LangGraph / MCP agents, Redis, Kafka, Langfuse and AWS are later phases. Do not
implement them unless asked. Architecture: `docs/architecture.md`; decisions: `docs/adr/`.

User constraints: synthetic data only; never hardcode credentials; ask before deleting files or
pushing to GitHub; never deploy to paid cloud services without approval.

## Commands (Windows PowerShell; Maven is not installed, use the wrapper)

```powershell
# Backend (from backend\)
.\mvnw.cmd test                                   # unit + @WebMvcTest slices + ModularityTest (no Docker)
.\mvnw.cmd verify                                 # + *IT integration tests (Testcontainers, Docker required)
.\mvnw.cmd test -Dtest="PaymentControllerSecurityTest#careAgentCannotSeeStats"
.\mvnw.cmd verify -Dit.test=SecurityIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false

# Frontend (from frontend\)
npm test ; npm run lint ; npm run build           # build = tsc -b + vite build
npx vitest run src/App.test.tsx -t "blocks a care agent"

# Full stack (from repo root)
.\scripts\new-dev-env.ps1                         # creates .env with random secrets (once)
docker compose up -d --build --wait               # app at http://localhost:3000
.\scripts\smoke-test.ps1                          # real Keycloak login + RBAC/CSRF/logout E2E checks
docker compose up -d --force-recreate --wait keycloak   # re-import realm after editing realm JSON or .env

# Host-run dev: docker compose up -d postgres keycloak; then `. .\scripts\dev-env.ps1` and
# `.\mvnw.cmd spring-boot:run` in backend\, and `npm run dev` in frontend\ (http://localhost:5173)
```

Surefire runs `*Test.java`; Failsafe runs `*IT.java`. Integration tests use `@IntegrationTest`
(`backend/src/test/java/com/payassist/support/`), which starts `pgvector/pgvector:0.8.7-pg17`.
Tests that **commit** writes use `@MutatingIntegrationTest` instead (separate context and container), so
read-only ITs can keep asserting exact seed counts; each mutating test picks payments no other test uses.

## Architecture essentials

**BFF security model** (`identity/security/SecurityConfig.java`): Spring Boot is an OAuth2
*confidential client* (`oauth2Login`, Authorization Code + PKCE). Tokens stay in the server session;
the browser only has the `PAYASSIST_SESSION` HttpOnly cookie. The SPA must never receive or store tokens.
- The SPA and BFF share one origin: nginx (`frontend/nginx/default.conf.template`) in Docker, the Vite
  proxy (`frontend/vite.config.ts`) in dev. Proxied paths: `/api`, `/oauth2`, `/login/oauth2`,
  `/logout`, `/actuator`. A new backend path prefix must be added to **both**.
- The Keycloak `ClientRegistration` is built by hand (`KeycloakClientRegistrationConfig`), not via
  discovery. The browser-facing issuer (`localhost:8180`) is used for authorize/logout and `iss`
  validation; the back-channel URL (`keycloak:8080` in Docker) for token/JWKS.
- CSRF: `XSRF-TOKEN` cookie must be echoed as the `X-XSRF-TOKEN` header (`apiFetch` in
  `frontend/src/api/client.ts` does this for non-GET requests) or as the `_csrf` form field (logout is a
  top-level form POST so the browser follows the redirect to Keycloak end-session).
- `/api/**` returns 401 (not a redirect) when unauthenticated; the SPA calls `/api/v1/me` to decide
  between the login page, the no-access page and the app.

**Roles**: Keycloak realm roles → `roles` claim (protocol mapper in `infra/keycloak/realm-payassist.json`)
→ `KeycloakRoleMapper` (allow-list) → `ROLE_*`. Enforce access in **both** URL rules in `SecurityConfig`
and `@PreAuthorize(Roles.*)` on controller methods. Mapped roles live on the `Authentication`, **not**
on the `OidcUser` principal, so read authorities from `Authentication`. Note that
`oidcLogin().authorities(...)` in tests sets both and hides this mistake.

**Modular monolith** (`com.payassist.*`): `identity`, `customer`, `payment`, `investigation`, `audit`, `shared` (OPEN).
`ModularityTest` (Spring Modulith) fails the build if a module touches another module's sub-packages
(`internal`, `web`, `security`) or creates a cycle. Cross-module access goes through top-level-package
types (e.g. `CustomerDirectory`, `PaymentViewedEvent`). Entities hold IDs, not JPA relations, across modules.
New capabilities (refund, policy/RAG, assistant) go in new top-level modules.

**Audit**: `PaymentQueryService.viewDetails` publishes `PaymentViewedEvent`; newer code publishes the generic
`shared.AuditRecord` (actor from `identity.CurrentActor`, never a parameter). `audit` writes the row
synchronously in the same read-write transaction (fail closed); `audit_events` is append-only. Keep detail
reads audited. Investigation reads (`Roles.INVESTIGATE_PAYMENTS`, ADMIN read-only) are checked on controller
**and** service. API contract: `docs/api.md`; learning guides: `docs/learning/`.

**Payment lifecycle**: status changes only through `PaymentLedger` (public interface; impl
`payment/internal/PaymentLedgerService`), which row-locks the payment, validates against
`PaymentTransitions` and appends to `payment_events` (append-only, enforced by a DB trigger; corrections
are new events). Business-rule failures throw `DomainException.Conflict` (409) / `.RuleViolation` (422)
with a stable `code` in Problem Details. Money: `BigDecimal` + ISO code via `MonetaryAmounts`, never rounded.
Compare users by `Actor.subject` (OIDC `sub`), never by username.

**Data**: Flyway owns the schema (`ddl-auto=validate`). Add schema changes as new `db/migration/V<n>__*.sql`.
Synthetic seed data is the repeatable `db/seed/R__synthetic_seed_data.sql`, loaded only by the `dev`
profile (`application-dev.yml`) and the `test` profile. Integration tests assert exact seed counts
(40 customers, 400 payments). pgvector is available in the image, but `CREATE EXTENSION vector` is
deliberately deferred to the RAG phase.

**Config**: all secrets come from env vars with no defaults (`application.yml`,
`PayAssistSecurityProperties` is `@Validated`). Tests use `application-test.yml` (dummy values).

## Gotchas

- A native PostgreSQL on this machine occupies 5432; Compose maps the container to `POSTGRES_HOST_PORT` (default 5433).
- Keycloak 26 user-profile validation rejects characters such as `(` `)` in first/last names. Invalid
  realm users get stuck on a VERIFY_PROFILE page at login.
- Keycloak's login cookies are `Secure`; browsers accept them on http://localhost, .NET doesn't.
  `smoke-test.ps1` copies them for localhost only.
- `frontend/.npmrc` has `legacy-peer-deps=true` to avoid an npm 11.3 crash (`reading 'edgesOut'`).
- Spring redirects unauthenticated *non-API* requests to login only when `Accept: text/html`; otherwise 401.
