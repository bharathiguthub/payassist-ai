# ADR 0011 — Refund requests, reservations and supervisor approval

**Status:** Accepted (Phase 2, milestone 4)

## Context
Agents must be able to request refunds; supervisors must approve them within limits; nobody may approve their
own request; ADMIN is read-only; nothing may ever be refunded twice or beyond the payment amount, even under
retries and concurrent clicks; future AI agents may *prepare* refunds but never approve them. No real money moves.

## Decision

### State machine
`PENDING_APPROVAL → APPROVED | REJECTED`, `ESCALATION_REQUIRED → REJECTED` (as data in `RefundRequestTransitions`).
`ESCALATION_REQUIRED → APPROVED` is reserved for a future `REFUND_MANAGER` role and is **not** implemented.
Creation chooses `ESCALATION_REQUIRED` when re-validated eligibility reports `SUPERVISOR_LIMIT_EXCEEDED` (or no
limit is configured for the currency), otherwise `PENDING_APPROVAL`.

### Reservations
Requests in `PENDING_APPROVAL` / `ESCALATION_REQUIRED` reserve their amount (`PendingRefundReservations`, the real
implementation of the M3 port). The eligibility engine computes `remaining = amount − recorded refunds − reserved`.
Approval consumes the reservation (the refund becomes a `REFUND_RECORDED` ledger event); rejection releases it.
Invariant: `Σ REFUND_RECORDED + Σ active reservations ≤ payment amount`.

### Locking, isolation and transactions
- One READ COMMITTED transaction per write. Step one is always `PaymentLedger.lockForRefund(paymentRef)`
  (a PostgreSQL row lock via JPA `PESSIMISTIC_WRITE`, held until commit); decisions then lock the
  refund request row. **Fixed lock order payment → request** in every path prevents deadlocks.
- READ COMMITTED (not REPEATABLE READ) is required: after waiting for the lock, each statement must see what the
  previous holder committed. Under REPEATABLE READ PostgreSQL would raise serialization errors instead.
- Approval re-runs the eligibility engine under the lock (excluding the request's own reservation); the refund
  window is judged at the request's submission time, everything else on current state. `INELIGIBLE` → 422
  `REFUND_NO_LONGER_ELIGIBLE` (e.g. a chargeback arrived meanwhile).
- Optimistic version (`refund_requests.version`) lets a supervisor send `expectedVersion`; mismatch → 409
  `CONCURRENT_MODIFICATION`.

### Exactly once
- `refund_decisions`: append-only (trigger), **unique per request** → at most one decision ever.
- `refund_decisions.ledger_event_id`: unique FK to `payment_events`, required iff APPROVED → one ledger refund per approval.
- The ledger itself still enforces `Σ refunds ≤ amount` (defence in depth).

### Idempotency
- `Idempotency-Key` header required on `POST /refunds` (8–64 chars `[A-Za-z0-9_-]`), unique per
  `(requested_by_subject, idempotency_key)` in the database.
- `request_fingerprint` = SHA-256 of `paymentRef|amount(at currency scale)|currency|reason`. Same key + same fingerprint →
  the original request is returned (**200**, `Idempotent-Replayed: true`); different fingerprint → 422
  `IDEMPOTENCY_KEY_REUSED` (as in the IETF Idempotency-Key draft).
- The key lookup runs **after** taking the payment lock, so a concurrent retry waits and then finds the committed
  original. A unique-constraint violation (concurrent same key on different payments) is caught outside the
  transaction and resolved as replay-or-422.

### Authorization and separation of duties
| Action | CARE_AGENT | CARE_SUPERVISOR | ADMIN |
|---|:-:|:-:|:-:|
| Create request | ✔ | ✔ | ✘ |
| Read / list requests | ✔ | ✔ | ✔ read-only |
| Approve (PENDING_APPROVAL, within limit, not own) | ✘ | ✔ | ✘ |
| Reject (PENDING_APPROVAL or ESCALATION_REQUIRED, not own) | ✘ | ✔ | ✘ |
| Approve ESCALATION_REQUIRED | ✘ | ✘ (403 `ESCALATION_REQUIRED`) | ✘ |

Role rules: URL rules (every refund route listed; anything else under `/api/v1/refunds` is `denyAll`) +
`@PreAuthorize` on controller and service. Business rules (403 with code): `SELF_APPROVAL_FORBIDDEN`,
`SELF_REJECTION_FORBIDDEN`, `ESCALATION_REQUIRED`, `APPROVAL_LIMIT_EXCEEDED` (limit re-checked at approval with the
current policy). Users are compared by immutable `sub`, never by username. `allowedActions` in every response tells
the UI what the current user may do; it is a hint, every action is re-checked.

Every care role can read all refund requests (the same scope as payment investigation access, so colleagues see each
other's pending reservations and avoid duplicates); `mine=true` filters to the caller's own requests.

### Audit
Every create / view / approve / reject writes an audit row with actor username + subject, action, resource, outcome
(`SUCCESS`, `DENIED`, `FAILED` — new `audit_events.outcome` column in V5), correlation ID and non-sensitive details.
Successes are written in the business transaction (fail closed). Refusals are written in a separate transaction after
the rollback. Role-based denials of authenticated users (URL rules or method security) are captured from Spring
Security's `AuthorizationDeniedEvent` as `ACCESS_DENIED` / `DENIED`.

### No money movement
Approval records `REFUND_RECORDED` (source `PAYASSIST`) in the payment ledger. A real integration would publish
that fact to a payment service provider through a transactional outbox (later phase).

### AI boundary
`RefundRequests` (`requestRefund`, `getRefundStatus`, `listRefunds`) is the tool-ready interface;
`RefundApprovals` (`approve`, `reject`) is human-only and must never be exposed as a tool.

## Keycloak: second supervisor
`tara.supervisor` was added to the running realm through the Admin API (no re-import), and to
`realm-payassist.json` with **pinned user IDs** for all five users, so a future re-import keeps every `sub`.
Re-importing (recreating the Keycloak container, which uses an embedded dev database) would otherwise give users
new IDs, end all Keycloak sessions and discard console-only changes; PostgreSQL is unaffected.

## Consequences
- Refund writes for one payment are serialised (short transactions; contention is per payment only).
- Above-limit requests can only be rejected until a REFUND_MANAGER role exists; their reservation persists until then.
- Tests that commit refund data use a documented allocation of seed payments (`RefundTestPayments`).
