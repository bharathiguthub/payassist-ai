# PayAssist AI — Architecture

PayAssist AI is an enterprise customer-support workspace for payment investigations. Customer care
executives search and investigate payments, check refund eligibility, query payment policies (RAG),
and work with AI agents. This document describes the **target architecture** across all phases and
exactly **what Phase 1 implements**.

Decision rationale lives in the ADRs in [`docs/adr/`](adr/).

---

## 1. Target architecture (all phases)

```
                              ┌────────────────────────── Browser ──────────────────────────┐
                              │  React + TypeScript SPA (no tokens; HttpOnly session cookie) │
                              └───────────────┬──────────────────────────────┬──────────────┘
                                              │ same origin                  │ login pages
                                              ▼                              ▼
                               ┌─────────────────────────┐        ┌────────────────────┐
                               │ nginx (static + proxy)  │        │ Keycloak (OIDC IdP)│
                               └────────────┬────────────┘        └─────────▲──────────┘
                                            ▼                               │ code→token (back channel)
┌───────────────────────────────────────────────────────────────────────────┴───────────────┐
│  Spring Boot 3.5 / Java 21 — Backend-for-Frontend + domain API (modular monolith)          │
│                                                                                            │
│  identity │ customer │ payment │ audit │ shared      ◄── Phase 1                            │
│  refund   │ policy (RAG, Spring AI + pgvector) │ assistant (chat orchestration)  ◄── later  │
└──────┬──────────────────┬──────────────────────────┬──────────────────────┬────────────────┘
       │ JDBC             │ cache / sessions          │ events               │ HTTP (token relay)
       ▼                  ▼                           ▼                      ▼
 PostgreSQL 17       Redis (later)              Kafka (later)       Python agent service (later)
 + pgvector                                                         LangGraph agents, calling
                                                                    PayAssist tools via MCP
                                         Langfuse (later): LLM traces, prompts, evals
```

| Concern | Choice | Phase |
|---|---|---|
| Identity | Keycloak, OIDC Authorization Code + PKCE, confidential BFF client | **1** |
| Session model | Server-side session, HttpOnly cookie, CSRF double-submit | **1** |
| Domain API | Spring Boot modular monolith, `/api/v1`, Problem Details errors | **1** |
| Data | PostgreSQL 17, Flyway, pgvector image | **1** (vector usage later) |
| Audit | Append-only `audit_events`, event-driven | **1** |
| Refund eligibility | Deterministic, versioned rules engine (`refund` module) | **2 (done)** |
| Policy search (RAG) | Spring AI, embeddings in pgvector | 3 |
| AI agents | Python LangGraph service; PayAssist tools exposed via MCP | 4 |
| Caching / shared sessions | Redis (Spring Session) | 4+ |
| Eventing | Kafka via transactional outbox | 4+ |
| LLM observability | Langfuse | 3+ |
| Hosting | Docker locally; AWS (ECS/EKS, RDS, Secrets Manager) | later, with approval |

---

## 2. Phase 1 scope

**Implemented**

- Secure login and logout via Keycloak (OIDC Authorization Code + PKCE through the BFF; RP-initiated logout).
- Role-based authorization: `CARE_AGENT`, `CARE_SUPERVISOR`, `ADMIN`, enforced by URL rules **and** method security.
- Customer care dashboard: payment search (reference prefix, customer reference, status, paging), payment details with masked customer email, supervisor statistics, admin audit log.
- Audit trail of every payment-detail view (who, what, when).
- PostgreSQL schema via Flyway; deterministic synthetic seed data (dev/test only).
- Docker Compose stack: PostgreSQL + pgvector, Keycloak (realm auto-import), backend, nginx-served frontend.
- Tests: unit, web-slice security, Testcontainers integration, module-boundary verification, frontend component tests, and a scripted end-to-end login smoke test.

**Deliberately not implemented**: refund eligibility, RAG, Spring AI, LLM calls, Python/LangGraph/MCP,
Redis, Kafka, Langfuse, AWS deployment.

---

## 3. Request flows

### 3.1 Login (OIDC Authorization Code + PKCE via the BFF)

```
Browser            nginx/BFF (Spring)                     Keycloak
   │ GET /api/v1/me ──► 401                                   │
   │ (SPA shows "Sign in")                                    │
   │ GET /oauth2/authorization/keycloak ─►                    │
   │      creates state, nonce, PKCE verifier (kept in session)
   │ ◄── 302 to /auth?code_challenge=S256(...)&state&nonce    │
   │ ───────────────────────────────────────────────────────► │ login page
   │ ◄──────────────────────── 302 /login/oauth2/code/keycloak?code&state
   │ GET callback ─►  verify state; POST /token (code + verifier + client secret) ─► │
   │                  ◄── ID/access/refresh tokens            │
   │                  validate ID token: signature (JWKS), iss, aud, exp, nonce
   │                  map realm roles → ROLE_*; store tokens in server session
   │ ◄── 302 to "/" + Set-Cookie PAYASSIST_SESSION (HttpOnly, SameSite=Lax)
```

The browser never receives a token. JavaScript cannot read the session cookie.

### 3.2 API call

`SPA → (cookie) → CorrelationIdFilter → Spring Security (session, CSRF on unsafe methods, URL role rules) → @PreAuthorize → service → repository`

### 3.3 Logout

The SPA submits a top-level `POST /logout` form with the `_csrf` token → Spring invalidates the session →
302 to Keycloak `end_session_endpoint` with `id_token_hint` and `post_logout_redirect_uri` → Keycloak ends
the SSO session → back to the SPA.

---

## 4. Backend module structure

```
com.payassist
├── identity   Roles (public API) · security/ (SecurityConfig, Keycloak client registration, role mapper) · web/ (/me)
├── customer   CustomerDirectory, CustomerSummary (public API) · internal/ (entity, repository, email masking)
├── payment    PaymentStatus, PaymentMethod, PaymentViewedEvent, PaymentLedger (write), PaymentHistory (read)
│              (public API) · internal/ (entities, state machine, ledger, history, queries) · web/
├── investigation  InvestigationService, InvestigationSummary (public API; future AI tool seam) · internal/ (rules) · web/
├── refund     RefundEligibilityService, RefundRequests (tool-safe), RefundApprovals (human-only), views
│              (public API) · internal/ (policy, rules engine, reservations, request workflow) · web/
├── audit      internal/ (entity, listeners for PaymentViewedEvent and AuditRecord) · web/ (admin audit API)
└── shared     OPEN module: Actor, AuditRecord, DomainException, MonetaryAmounts, Problem Details handler,
               PageResponse, correlation IDs, Clock
```

Rules (checked by `ModularityTest` using Spring Modulith):

- Only a module's top-level package is visible to other modules; sub-packages are private.
- No dependency cycles. Current graph: `investigation → payment → customer`, `refund → payment`, `audit → payment` (event type only), all → `identity`, `shared`.
- No JPA relationships across modules (payments reference customers by ID).
- Modules talk through public services (`CustomerDirectory`) or domain events (`PaymentViewedEvent`).

**Where later phases plug in**

| Future capability | Plug-in point |
|---|---|
| Refund eligibility | New `refund` module consuming `payment`'s public API |
| Policy RAG | New `policy` module; Flyway migration `CREATE EXTENSION vector`; Spring AI `VectorStore` on the same PostgreSQL |
| AI agents (LangGraph) | Python service calls PayAssist as MCP tools; the tools wrap existing read services such as `PaymentQueryService`, with the user's identity propagated (token exchange / relay), so agents never get more access than the user |
| Kafka | `PaymentViewedEvent` and future events published via a transactional outbox; no change to publishers |
| Redis | Spring Session store for horizontal scaling of the BFF; cache for reference data |
| Langfuse | Tracing in the agent service and Spring AI calls; correlation ID already flows through MDC |

---

## 5. Data model (Phase 1 + Phase 2 so far)

| Table | Purpose | Notes |
|---|---|---|
| `customers` | Synthetic customers | `customer_ref` unique; email always masked in API responses |
| `payments` | Synthetic payments | Status/method enforced with CHECK constraints; **no PAN, CVV or bank account data** |
| `audit_events` | Access audit trail | Append-only (DB trigger since V3); written in the same transaction as the operation it audits; actor subject + correlation ID since V3; non-sensitive JSONB `details` since V4; `outcome` SUCCESS/DENIED/FAILED since V5 |
| `refund_requests` | Refund requests (Phase 2) | Reserve their amount while pending; idempotency key unique per requester; optimistic `version` |
| `refund_decisions` | Approve/reject decisions | Append-only, one per request; approvals reference their unique `REFUND_RECORDED` ledger event |
| `payment_events` | Payment history (Phase 2) | Append-only (DB trigger); written only by `PaymentLedger` together with the status change; see [ADR 0008](adr/0008-payment-lifecycle-and-event-history.md) |

Migrations: `backend/src/main/resources/db/migration` (all environments).
Seed data: `backend/src/main/resources/db/seed/R__synthetic_seed_data.sql`, a repeatable, idempotent
migration loaded **only** by the `dev` profile and tests.

---

## 6. Phase 2 progress

| Milestone | Status | Docs |
|---|---|---|
| M1 Payment lifecycle + event history | Done | [ADR 0008](adr/0008-payment-lifecycle-and-event-history.md), [guide](learning/phase2-m1-payment-lifecycle.md) |
| M2 Timeline + investigation summary | Done | [ADR 0009](adr/0009-deterministic-investigation-summary.md), [guide](learning/phase2-m2-investigation.md), [API](api.md) |
| M3 Refund eligibility engine | Done | [ADR 0010](adr/0010-refund-eligibility-engine.md), [guide](learning/phase2-m3-refund-eligibility.md), [API](api.md) |
| M4 Refund requests and supervisor approval | Done | [ADR 0011](adr/0011-refund-requests-and-approval.md), [guide](learning/phase2-m4-refund-approval.md), [API](api.md) |
| M5–M8 Disputes, audit hardening, frontend polish, docs | Planned | |

## 7. Security controls (Phase 1)

| Threat | Control |
|---|---|
| Token theft via XSS | Tokens never reach the browser (BFF); session cookie is HttpOnly; strict CSP from nginx |
| Authorization-code interception | PKCE S256 enforced by the Keycloak client and sent by Spring; confidential client secret |
| CSRF | Cookie-based CSRF token required on all unsafe methods, including logout; SameSite=Lax session cookie |
| Session fixation | Session ID changes on login |
| Broken access control | Deny-by-default; URL rules + `@PreAuthorize`; allow-listed role mapping; UI guards are cosmetic only |
| Injection | JPA Criteria with bound parameters; input pattern validation; LIKE wildcards escaped |
| Data exposure | Masked customer email; no card data; DTOs instead of entities; no stack traces in errors |
| Secrets leakage | All secrets from environment; `.env` git-ignored; startup fails if a secret is missing |
| Brute force | Keycloak brute-force detection; password policy |
| Clickjacking | `X-Frame-Options: DENY`, CSP `frame-ancestors 'none'` |
| Log injection / traceability | Correlation IDs validated before logging; per-request ID in every log line |
| Accountability | Audit log of payment-detail access |

---

## 8. Environments and configuration

| Setting | Local Docker | Local host-run | Production (future) |
|---|---|---|---|
| Public URL | `http://localhost:3000` (nginx) | `http://localhost:5173` (Vite proxy) | HTTPS domain behind a load balancer |
| Keycloak | `start-dev`, realm import | same container | `start`, TLS, external DB, managed realm config |
| Cookie `Secure` | false (HTTP) | false | **true** (default when unset) |
| Spring profile | `dev` (seed + OpenAPI) | `dev` | default (no seed, no OpenAPI) |
| Secrets | `.env` | `.env` via `scripts/dev-env.ps1` | AWS Secrets Manager / SSM |
