# PayAssist AI

Enterprise AI-powered payment customer-support workspace. Customer care executives investigate
payments, and (in later phases) check refund eligibility, search payment policies with RAG, and work
with AI agents.

> **Current status: Phase 2 (M1–M6 done).** Phase 1 delivered secure login, role-based access, and a customer care dashboard
> for payment investigation over **synthetic data only**. No AI features yet.
> See [docs/architecture.md](docs/architecture.md) and the decision records in [docs/adr/](docs/adr/).
> **Phase 2 in progress** (branch `feature/phase2-payment-domain`): payment lifecycle, timeline,
> investigation summary, refund eligibility, refund requests with supervisor approval, and dispute case management
> are done, plus milestone 6: a tamper-evident (hash-chained, externally checkpointed) audit trail, login/logout
> auditing, least-privilege database roles and an audited admin
> audit-log view.
> No real money ever moves: approvals record simulated refunds. API: [docs/api.md](docs/api.md); learning guides: [docs/learning/](docs/learning/).

| Layer | Technology |
|---|---|
| Backend (BFF + API) | Java 21, Spring Boot 3.5, Spring Security (OAuth2 client), Spring Data JPA, Flyway, Spring Modulith |
| Frontend | React 19, TypeScript, Vite, React Router, TanStack Query |
| Identity | Keycloak 26 (OIDC Authorization Code + PKCE), roles `CARE_AGENT`, `CARE_SUPERVISOR`, `ADMIN` |
| Data | PostgreSQL 17 with pgvector available |
| Runtime | Docker Compose (local) |

---

## 1. Prerequisites (Windows)

| Tool | Version | Check in PowerShell |
|---|---|---|
| Docker Desktop (WSL 2 backend) | recent | `docker version` (the "Server" section must appear) |
| Java JDK | 21 | `java -version` |
| Node.js | 22 LTS | `node -v` |
| Git | any | `git --version` |

Maven does **not** need to be installed: the repository includes the Maven Wrapper (`backend\mvnw.cmd`).

If PowerShell blocks the helper scripts, allow local scripts for your user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

---

## 2. Quick start: everything in Docker

```powershell
cd C:\Projects\payassist-ai

# 1. Create .env with random local secrets (prints the demo-user password)
.\scripts\new-dev-env.ps1

# 2. Build and start PostgreSQL, Keycloak, backend and frontend (first build takes a few minutes)
docker compose up -d --build --wait

# 3. Check every service is healthy
docker compose ps

# 4. Optional: end-to-end check of login, roles, CSRF and logout
.\scripts\smoke-test.ps1
```

Open **http://localhost:3000** and click **Sign in with company SSO**.

### Synthetic demo users

All five users share the password stored in `.env` as `PAYASSIST_DEMO_PASSWORD`:

```powershell
Select-String -Path .env -Pattern '^PAYASSIST_DEMO_PASSWORD'
```

| Username | Role | What they can see |
|---|---|---|
| `agent.alice` | CARE_AGENT | Payment search and details, refund eligibility, refund requests, dispute cases |
| `sam.supervisor` | CARE_SUPERVISOR | + Supervisor statistics, refund approvals, dispute review |
| `tara.supervisor` | CARE_SUPERVISOR | Second supervisor (separation of duties: approves Sam's requests) |
| `ada.admin` | ADMIN | + Audit log; refunds and disputes read-only (no requests, approvals or decisions) |
| `nora.norole` | (none) | "No access" page; every API returns 403 |

Try searching for `PAY-0000` (payment reference prefix) or `CUS-00001` (customer reference), or
filter by status.

### Service URLs

| URL | What |
|---|---|
| http://localhost:3000 | PayAssist app (nginx serving the SPA and proxying to the backend) |
| http://localhost:8180 | Keycloak (admin console: `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD` from `.env`) |
| http://localhost:8080/actuator/health | Backend health |
| http://localhost:8080/swagger-ui.html | OpenAPI UI (`dev` profile; sign in through the app first) |
| `localhost:5433` | PostgreSQL (user and password from `.env`) |

All ports bind to `127.0.0.1` only.

### Stop and reset

```powershell
docker compose down          # stop; keeps database data
docker compose down -v       # stop and DELETE the database volume (synthetic data is recreated on next start)
```

Keycloak runs in dev mode and re-imports `infra/keycloak/realm-payassist.json` whenever its container
is recreated. After changing `.env` secrets or the realm file, run:

```powershell
docker compose up -d --force-recreate --wait keycloak backend
```

---

## 3. Development mode: hot reload

Run only the infrastructure in Docker, and the backend and frontend on Windows:

```powershell
# Terminal 1: infrastructure
cd C:\Projects\payassist-ai
docker compose up -d --wait postgres keycloak
docker compose up --no-deps migrate   # once per new migration: Flyway as schema owner + enables payassist_app login

# Terminal 2: backend on http://localhost:8080
cd C:\Projects\payassist-ai
. .\scripts\dev-env.ps1                 # leading dot: loads .env into this session
cd backend
.\mvnw.cmd spring-boot:run

# Terminal 3: frontend on http://localhost:5173 (proxies /api, /oauth2, /login/oauth2, /logout to :8080)
cd C:\Projects\payassist-ai\frontend
npm install
npm run dev
```

Open **http://localhost:5173**. If the Docker `backend` container is running, stop it first
(`docker compose stop backend frontend`) to free port 8080.

---

## 4. Tests

```powershell
# Backend unit + web-slice + module-boundary tests (no Docker needed)
cd C:\Projects\payassist-ai\backend
.\mvnw.cmd test

# Backend unit + integration tests (Testcontainers starts PostgreSQL 17 + pgvector; Docker must be running)
.\mvnw.cmd verify

# Single test class / single method
.\mvnw.cmd test -Dtest=KeycloakRoleMapperTest
.\mvnw.cmd test -Dtest="PaymentControllerSecurityTest#careAgentCannotSeeStats"
.\mvnw.cmd verify -Dit.test=SecurityIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false

# Frontend
cd C:\Projects\payassist-ai\frontend
npm test                                   # Vitest, single run
npx vitest run src/App.test.tsx            # single file
npx vitest run -t "blocks a care agent"    # by test name
npm run lint
npm run build                              # type-check + production build

# End-to-end against the running stack (real Keycloak login)
cd C:\Projects\payassist-ai
.\scripts\smoke-test.ps1                                  # Docker stack on :3000
.\scripts\smoke-test.ps1 -BaseUrl http://localhost:5173   # dev mode
```

| Suite | Location | Count |
|---|---|---|
| Backend unit + slice | `backend/src/test/**/*Test.java` | 32 |
| Backend integration | `backend/src/test/**/*IT.java` | 19 |
| Frontend | `frontend/src/**/*.test.ts(x)` | 18 |
| Smoke (E2E) | `scripts/smoke-test.ps1` | 25 checks |

---

## 5. Project layout

```
payassist-ai/
├── backend/            Spring Boot BFF + API (Maven Wrapper, Dockerfile)
│   └── src/main/java/com/payassist/{identity,customer,payment,audit,shared}
├── frontend/           React SPA (Vite, nginx template, Dockerfile)
├── infra/keycloak/     Realm import: roles, BFF client, synthetic users (secrets as ${ENV} placeholders)
├── docs/               Architecture and ADRs
├── scripts/            PowerShell helpers: new-dev-env, dev-env, smoke-test
├── docker-compose.yml
└── .env.example        Template; the real .env is git-ignored
```

---

## 6. Configuration

The backend reads all settings from environment variables. Required secrets have **no defaults**, so
startup fails rather than running insecurely.

| Variable | Purpose |
|---|---|
| `PAYASSIST_DB_URL`, `PAYASSIST_DB_USERNAME`, `PAYASSIST_DB_PASSWORD` | Database connection |
| `PAYASSIST_PUBLIC_BASE_URL` | Origin users browse to; used for OIDC redirect and logout URIs |
| `KEYCLOAK_ISSUER_URI` | Browser-facing realm URL; must equal the token `iss` |
| `KEYCLOAK_BACKCHANNEL_URI` | Optional; realm URL as reached by the backend (in Docker: `http://keycloak:8080/...`) |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | Confidential client credentials |
| `PAYASSIST_COOKIE_SECURE` | Defaults to `true`; set `false` only for plain-HTTP localhost |
| `SPRING_PROFILES_ACTIVE=dev` | Loads synthetic seed data and enables OpenAPI. **Never in production.** |

---

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `ports are not available ... 5432` | A local PostgreSQL uses 5432. Compose uses `POSTGRES_HOST_PORT` (default 5433). |
| `failed to connect to the docker API` | Start Docker Desktop and wait until `docker version` shows the Server section. |
| Keycloak "Invalid parameter: redirect_uri" | Use http://localhost:3000 or :5173 exactly (not 127.0.0.1); only these are registered. |
| Login loop, or "cookie not found" in Keycloak | Use `localhost` consistently; clear cookies for localhost. |
| Demo password rejected after editing `.env` | The realm is imported once per container: `docker compose up -d --force-recreate keycloak`. Re-importing ends all Keycloak sessions and discards Admin Console changes; user IDs are kept because the realm JSON pins them. |
| `tara.supervisor` cannot sign in on an older local stack | Your Keycloak container predates the realm change. Either re-import as above, or add the user without a restart through the Keycloak Admin API (`kcadm.sh`) as described in ADR 0011. |
| `npm install` crashes with `reading 'edgesOut'` | npm 11.3 bug; `frontend/.npmrc` sets `legacy-peer-deps=true`. Run from `frontend\`. |
| Backend exits with `Binding to target ... failed` / missing property | An environment variable is missing; in dev mode dot-source `scripts\dev-env.ps1` first. |

---

## 8. Security notes

- The backend connects to PostgreSQL as the restricted role `payassist_app`; the schema owner is used only by the one-off
  `migrate` job. Existing `.env` files from before milestone 6.3/6.4 need `scripts/add-missing-secrets.ps1` once
  (adds any missing generated secret, never prints it). Apply, rotate and roll back:
  [docs/runbooks/runtime-db-role.md](docs/runbooks/runtime-db-role.md).
- Audit checkpoints: `.\scripts\audit-checkpoint.ps1 -Create` verifies the audit hash chain and appends an HMAC-signed
  checkpoint to `audit-checkpoints/` (outside PostgreSQL, git-ignored; keep a copy elsewhere). Run it at least daily and
  after deployments: [docs/runbooks/audit-checkpoints.md](docs/runbooks/audit-checkpoints.md).
- Synthetic data only: no real customers, payments, card numbers or bank details.
- `.env` holds **local development** secrets only and is git-ignored. Production secrets belong in a
  secrets manager.
- Keycloak `start-dev` and HTTP are for local development only. Production requires TLS,
  `PAYASSIST_COOKIE_SECURE=true`, Keycloak in `start` mode with an external database, and production
  redirect URIs.

## 9. Roadmap (not implemented)

Phase 2 M7–M8 polish · Phase 3 policy RAG (Spring AI, pgvector, Langfuse) · Phase 4 AI agents
(Python LangGraph, MCP tools, Kafka, Redis) · AWS deployment. See [docs/architecture.md](docs/architecture.md).
