# Learning guide — Refund requests and supervisor approval (Phase 2, M4)

Code: `refund/RefundRequests`, `refund/RefundApprovals`, `refund/internal/RefundRequestService`,
`refund/internal/RefundRequestTransitions`, `payment/PaymentLedger.lockForRefund`,
`identity/security/AuthorizationDenialAuditor`, `db/migration/V5__refund_requests.sql`.
API: [docs/api.md](../api.md). Decision record: [ADR 0011](../adr/0011-refund-requests-and-approval.md).

## 1. Business problem
Refunds are where support tools lose money: double refunds after a double click, two agents refunding the same
payment at once, an agent approving their own refund, a large refund slipping through without senior review, a
refund issued after a chargeback already returned the money. The workflow must make these *impossible*, not just
unlikely, while staying fast for the common case and fully auditable.

## 2. Architecture and internal workflow
```
Agent ─POST /refunds (Idempotency-Key, CSRF)──►  RefundRequestService.requestRefund         [one READ COMMITTED tx]
   1. lock payment row (PaymentLedger.lockForRefund)        ◄── all refund writes for this payment queue here
   2. same key already used by this user? → replay (200) or IDEMPOTENCY_KEY_REUSED (422)
   3. reserved = Σ active requests;  eligibility engine (M3) on authoritative facts
   4. INELIGIBLE → 422 REFUND_NOT_ELIGIBLE (+reasons)  |  above limit → ESCALATION_REQUIRED  |  else PENDING_APPROVAL
   5. INSERT refund_requests (reserves the amount) · ledger REFUND_REQUESTED/ESCALATED · audit SUCCESS   → commit

Supervisor ─POST /refunds/{ref}/approve──►  RefundRequestService.approve                       [one tx]
   1. lock payment row, then refund request row (fixed order → no deadlocks)
   2. expectedVersion? · not own request? · not escalated? · state allows APPROVED? · within limit?
   3. re-validate eligibility (other reservations count; window judged at submission time)
   4. ledger REFUND_RECORDED (simulated refund) · INSERT refund_decisions(ledger_event_id) · status APPROVED
   5. audit SUCCESS → commit                     (any refusal: rollback, then audit DENIED/FAILED in its own tx)
```

### Refund state machine
```
create ─► PENDING_APPROVAL ──approve──► APPROVED      (final; reservation consumed, refund recorded once)
             │       └──────reject───► REJECTED      (final; reservation released)
             └(above limit)► ESCALATION_REQUIRED ──reject──► REJECTED
                              (approve needs a future REFUND_MANAGER role)
```
The table lives in `RefundRequestTransitions`; `RefundRequest.transitionTo` is the only way to change status.

## 3. Design patterns
- **State machine as data** (same pattern as the payment lifecycle).
- **Reservation pattern** (a.k.a. "holds"): pending requests reserve capacity, so the system never promises more
  than exists; approval converts the hold into a fact, rejection releases it.
- **Idempotency key + request fingerprint** (Stripe / IETF draft): safe client retries.
- **Pessimistic lock for invariants, optimistic version for stale UI** — each for what it is good at.
- **Database constraints as the last line of defence**: unique decision per request, unique ledger event per
  approval, append-only triggers, unique idempotency key.
- **Ports & adapters**: `RefundReservations` port (M3) gets its real adapter; `PaymentLedger` is the payment
  module's port for writes; money movement would be another port (simulated today).
- **Interface segregation for AI safety**: `RefundRequests` (tool-safe) vs `RefundApprovals` (human-only).
- **Server-computed affordances** (`allowedActions`): the UI renders what the server would allow, never decides.

## 4. Transaction consistency and failure scenarios
### Database locking and isolation
- `SELECT … FOR UPDATE` on the payment row serialises every refund write for that payment. Other payments are unaffected.
- **Lock ordering** (payment, then request) is the classic deadlock prevention: two transactions can never hold
  the locks in opposite orders.
- **READ COMMITTED** is the right isolation with explicit locks: after acquiring the lock, a new statement sees the
  previous holder's committed rows (e.g. the other agent's reservation). With REPEATABLE READ the snapshot would be
  older than the lock and PostgreSQL would abort with a serialization error (safe, but every caller would need retries).
- Optimistic `version` protects the *human* step: a supervisor approving what they looked at five minutes ago.

### Idempotency design
| Situation | Result |
|---|---|
| Same user, same key, same payload (retry after timeout) | 200, `Idempotent-Replayed: true`, original request, nothing new written |
| Same user, same key, different payload | 422 `IDEMPOTENCY_KEY_REUSED` |
| Six concurrent retries with the same key | exactly one row; five replays (tested) |
| Different users, same key | independent requests (scope is per user) |
| `10` vs `10.00` | same fingerprint (amount normalised to currency scale first) |

### Transaction consistency
| Scenario | Outcome (tested) |
|---|---|
| 8 agents request 30% each at once | exactly 3 created, 5 × 422 `REFUND_NOT_ELIGIBLE` |
| 4 supervisor clicks on Approve at once | 1 approval, 3 × 409; one decision row, one `REFUND_RECORDED` |
| Approve and Reject race | exactly one decision; ledger consistent with it |
| Chargeback arrives between request and approval | 422 `REFUND_NO_LONGER_ELIGIBLE`; request stays pending |
| Audit insert fails during approval | whole approval rolls back (fail closed) |
| Crash after commit, client retries | idempotency replay returns the committed request |
| Ledger check | `Σ REFUND_RECORDED = Σ APPROVED requests` and `+ reservations ≤ amount` per payment |

## 5. Security implications
### Separation of duties
- Supervisors cannot decide on their **own** requests (approve or reject), compared by immutable `sub`.
- ADMIN can read everything but cannot create, approve or reject (platform administration ≠ money authority).
- Approval limits are re-checked at approval time with the current policy; above-limit requests need escalation
  that does not exist yet, so they can only be rejected.
- `tara.supervisor` exists so SoD can be exercised end to end (smoke test: Sam's own approval is refused; Tara decides).

### Human-in-the-loop approval
The system computes eligibility and reservations deterministically, but **a person** makes the money decision.
The approval UI shows eligibility codes and policy version, and every decision is attributable, versioned and audited.

### Other controls
- Authorization in three layers (URL, controller, service) plus business rules; unknown refund routes are denied.
- CSRF on every POST; BFF session cookie only; no tokens in the browser.
- Reason codes are enums, not free text: no personal data can be typed into refund records or audit details.
- Audit outcome `SUCCESS` / `DENIED` / `FAILED`, with actor subject and correlation ID; role denials captured from
  Spring Security events.
- No real money movement anywhere; approval records a simulated refund fact.

## 6. Future AI agent integration
- A Spring AI `@Tool` adapter would wrap `RefundRequests`: `requestRefund` (draft-and-submit for approval),
  `getRefundStatus`, `listRefunds`. Each runs under the **user's** security context (the agent acts on behalf of
  the signed-in agent via token exchange in the Python/MCP service), so it can never exceed that user's roles.
- The agent supplies its own idempotency key per intended action, so model retries cannot create duplicates.
- `RefundApprovals` is never registered as a tool. Even a compromised prompt cannot approve: the approval endpoint
  requires the CARE_SUPERVISOR role, a different person than the requester, and a limit check.
- Tool results are the versioned records (`RefundRequestView`, `RefundEligibility`) with stable codes, which the
  model explains rather than invents. Audit rows would gain an "on behalf of / via agent" field.

## 7. AI Lead Engineer interview questions
1. Walk through what happens when two supervisors click Approve on the same request at the same millisecond.
2. Why lock the payment row rather than the refund request row when creating a request?
3. Why must the isolation level be READ COMMITTED with `FOR UPDATE` here? What breaks under REPEATABLE READ?
4. How do you design idempotency keys: scope, storage, fingerprinting, replay semantics, expiry?
5. What is a reservation, and why is "check balance, then write" without one a race condition?
6. Which invariants are enforced by the database and which by code? Why both?
7. How would you let an LLM agent submit refund requests safely? What must it never be able to do, and how do you enforce that structurally rather than by prompt?
8. Define separation of duties for refunds. How do you test it end to end?
9. An approval was audited but the ledger shows no refund. Is that possible in this design? Why not?
10. How would you add a REFUND_MANAGER escalation tier without breaking existing data or clients?
11. Where would a transactional outbox fit when you integrate a real payment service provider?
