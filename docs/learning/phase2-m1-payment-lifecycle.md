# Learning guide — Payment lifecycle and event history (Phase 2, M1)

Code: `payment/PaymentLedger`, `payment/internal/PaymentLedgerService`, `payment/internal/PaymentTransitions`,
`db/migration/V2__payment_lifecycle.sql`. Decision record: [ADR 0008](../adr/0008-payment-lifecycle-and-event-history.md).

## 1. Business problem
A care executive taking a call ("I was charged but the merchant says I wasn't") needs to know exactly what
happened to a payment and in what order. Refunds and disputes then *change* the payment, and those changes
must never contradict each other: a payment cannot be refunded twice beyond its amount, a failed payment
cannot be settled, and nobody may quietly rewrite what happened. Phase 1 only stored the current status.

## 2. Architecture and internal workflow
```
caller (refund/dispute module, processor feed)
   │  RecordPaymentEvent(paymentRef, type, amount?, reason?, actor, source, occurredAt?, expectedVersion?)
   ▼
PaymentLedger.record()  ── one @Transactional unit ───────────────────────────────────────────┐
  1. reject system-only types, malformed reason codes, future timestamps (no DB access yet)   │
  2. SELECT payment … FOR UPDATE (row lock)                    ◄── concurrent writers wait here │
  3. expectedVersion == payment.version ?  else 409 CONCURRENT_MODIFICATION                  │
  4. PaymentTransitions.isAllowed(status, type, method) ?  else 409 INVALID_STATE_TRANSITION │
  5. amount: same currency, ≤ minor units, ≤ payment amount; refunds: Σ refunds ≤ amount     │
  6. target status (REFUNDED vs PARTIALLY_REFUNDED; restored status on DISPUTE_WON)          │
  7. INSERT payment_events (sequence_no = max + 1, actor, source, correlation ID)            │
  8. UPDATE payments SET status, updated_at, version = version + 1                           │
COMMIT (lock released) ◄───────────────────────────────────────────────────────────────────────┘
```
The status lives on `payments` (fast to query, Phase 1 code unchanged); the *history* lives in
`payment_events`. Both are written in the same transaction, so they cannot disagree.

## 3. Design patterns used
- **State machine as a table** (`EnumMap<event, allowed-from-statuses>`): the whole lifecycle is visible in one place and testable exhaustively (`PaymentTransitionsTest` checks all 90 status × lifecycle-event pairs).
- **Append-only event log / audit log** with a DB trigger: immutability enforced below the application.
- **Hybrid state + event log** (not full event sourcing): current state for queries, events for history.
- **Command object** (`RecordPaymentEvent`) with "wither" methods; **result object** (`RecordedPaymentEvent`).
- **Interface + package-private implementation** (`PaymentLedger` / `PaymentLedgerService`): other modules depend only on the contract; Spring Modulith enforces it.
- **Pessimistic lock for cross-row invariants, optimistic version for stale-read detection.**
- **Value validation helper** (`MonetaryAmounts`): no silent rounding; amounts at currency scale.

## 4. Transaction consistency and failure scenarios
| Scenario | What happens |
|---|---|
| Two agents refund 30% each, 8 times concurrently | The row lock serialises them; each re-reads Σ refunds under the lock; exactly 3 succeed, 5 get 422 `REFUND_EXCEEDS_PAYMENT_AMOUNT` (`PaymentLedgerIT`). |
| UI shows version 7, payment is now version 8 | Caller passes `expectedVersion=7` → 409 `CONCURRENT_MODIFICATION`; nothing written. |
| Event insert fails (e.g. constraint) | Whole transaction rolls back; status unchanged. |
| Invalid transition (CAPTURE a FAILED payment) | 409 before any write; version and history unchanged (tested). |
| Processor message arrives late | Stored with its real `occurred_at`; `sequence_no` records arrival order; timeline flags it. |
| Someone runs `UPDATE payment_events …` | Trigger raises "append-only"; statement fails. |
| Upgrade of a Phase 1 database | V2 adds one `LEGACY_SNAPSHOT` per payment; existing rows unchanged (`MigrationUpgradeIT`). |

Why not `SERIALIZABLE` isolation? It would also be correct but turns contention into serialization failures that every caller must retry; a targeted row lock is simpler and cheaper.

## 5. Security implications
- One write path means one place to review for correctness; other modules cannot touch `payments.status`.
- Actor stored as immutable subject + display username; humans cannot pose as `system:` actors.
- The ledger does not check roles itself (it is also used by system feeds); every user-facing caller must authorise first. This is documented on the interface and will be covered by each caller's tests.
- Append-only trigger protects against application bugs and casual tampering; full tamper resistance needs a separate low-privilege DB role (see ADR 0008, Consequences).
- No card numbers, CVV or bank details are stored or accepted.

## 6. Interview questions (AI Lead Engineer)
1. Why combine a status column with an event log instead of pure event sourcing? What would you lose and gain?
2. Explain how you guarantee Σ refunds ≤ captured amount under concurrency. Why is optimistic locking alone insufficient?
3. When would you choose `SERIALIZABLE` isolation over `SELECT … FOR UPDATE`?
4. How do you order events that arrive out of order? What are business time and system time?
5. How would you make an append-only table tamper-evident, not just tamper-resistant (hash chains, WORM storage)?
6. Why must money never be a `double`? What happens with currencies that have 0 or 3 minor units?
7. How does a modular monolith keep other modules from bypassing the ledger? What tests enforce it?
8. An AI agent will one day propose state changes. Where in this design must a human approval sit, and why should the ledger itself not be an AI tool?
