# ADR 0012 — Dispute case management

**Status:** Accepted (Phase 2, milestone 5). Decisions A1–A3 and D1–D12 approved by the product owner.

## Context
Cardholders dispute payments through their bank, card network or wallet provider. Care staff need a case file to
investigate, collect evidence, get a supervisor's approval before evidence goes out, and record the outcome. Disputes
interact with refunds (the customer must never be paid twice) but are a different process: a refund is the merchant
giving money back; a dispute is a third party deciding whether the cardholder gets it back.

## Decision

### Scope and separation (D1, D2)
- A **dispute case** covers bank / card-network / wallet-provider disputes only. Customer refund wishes remain
  refund requests (ADR 0011). Neither workflow creates or resolves the other.
- The **case lifecycle is separate from money**. Opening a case records `DISPUTE_OPENED`: the payment becomes
  **DISPUTED, an investigation status — nothing is reversed or charged back**. Only the outcome changes the payment's
  financial status: WON → `DISPUTE_WON` (pre-dispute status restored), LOST → `DISPUTE_LOST` (CHARGED_BACK),
  WITHDRAWN → `DISPUTE_WITHDRAWN` (restored; new ledger event, A2). No money moves in PayAssist.

### State machine (as data, `DisputeTransitions`)
```
OPEN ─start─► UNDER_INVESTIGATION ─request review (≥1 evidence)─► PENDING_REVIEW ─approve submission─► EVIDENCE_SUBMITTED
                     ▲                                                   │                              │ record outcome
                     └──────────────── return for investigation ─────────┘                              ▼
                                                                                                    WON | LOST
OPEN | UNDER_INVESTIGATION | PENDING_REVIEW ─accept liability─► LOST          any non-final ─withdraw─► WITHDRAWN
```

| Transition | Who | Separation of duties | Payment ledger |
|---|---|---|---|
| start investigation, request review | CARE_AGENT, CARE_SUPERVISOR | — | `DISPUTE_UPDATED` (timeline only) |
| return for investigation | CARE_SUPERVISOR | — | `DISPUTE_UPDATED` |
| approve submission | CARE_SUPERVISOR | not the review requester | `DISPUTE_UPDATED` |
| record outcome WON / LOST | CARE_SUPERVISOR | — (records a network fact) | `DISPUTE_WON` / `DISPUTE_LOST` |
| accept liability | CARE_SUPERVISOR | not the case opener | `DISPUTE_LOST` |
| withdraw | CARE_SUPERVISOR | — | `DISPUTE_WITHDRAWN` |

Separation of duties compares **immutable Keycloak user IDs** (`sub`), never usernames (D6). Notes and evidence can
be added by agents and supervisors to non-final cases. ADMIN reads everything and changes nothing (D7, D12).

### Money and deadlines (D3, D4)
- A case covers the **full unrefunded amount** at opening (payment amount − recorded refunds). A stated amount must
  match (422 `DISPUTED_AMOUNT_MISMATCH`). Partial disputes are out of scope: the ledger has no partial-chargeback state.
- `responseDueAt` is **optional and only as provided** from the notification. PayAssist computes no deadlines and
  encodes no network rules or reason-code schemes. Overdue is a flag and filter; nothing changes automatically.
- Reason categories are network-neutral; a network's own code can be stored verbatim (`networkReasonCode`, ≤ 16 chars).

### Refund interaction (D5, clarification 2)
1. Opening requires SETTLED or PARTIALLY_REFUNDED (the payment state machine decides; 409 otherwise).
2. While a case is active the payment is DISPUTED: new refund requests and approval of pending ones are refused
   (existing M3/M4 rules: `PAYMENT_DISPUTED`, `REFUND_NO_LONGER_ELIGIBLE`). Pending reservations are **preserved**.
3. After WON / WITHDRAWN, preserved pending requests can be approved again (re-validated); after LOST they can never
   be approved (`PAYMENT_CHARGED_BACK`) and a supervisor rejects them, releasing the reservation.
4. Double payment is impossible: the ledger's `Σ refunds ≤ amount`, refund re-validation under the payment lock, and the
   CHARGED_BACK block. Tested, including a refund approval racing a dispute opening.

### Consistency, concurrency, idempotency (A3)
- One READ COMMITTED transaction per write; opening and transitions lock the **payment row, then the case row**
  (`PaymentLedger.lockForUpdate`, shared with refunds). One lock order for refunds and disputes → no deadlocks.
- One active case per payment: checked under the lock and enforced by a **partial unique index**.
- `expectedVersion` is required on every transition (optimistic locking) → stale screens get 409.
- Case creation, notes and evidence take an `Idempotency-Key` (per user, SHA-256 fingerprint; replay 200, conflicting
  payload 422), as in ADR 0011.
- Case history (`dispute_case_events`), notes and evidence are **append-only** (triggers); each ledger-affecting history
  entry references its unique payment event.

### Notes (D8, clarification 4)
Append-only plain text ≤ 2000 chars. `TextGuard` rejects card-number-like digit runs (Luhn), email addresses and control
characters, without echoing the text. Notes are never copied into audit details. The UI renders them as text
(`white-space: pre-wrap`), never as HTML. Evidence is metadata only (type, external document reference, description).

### Audit
`DISPUTE_CASE_OPEN`, `_VIEW`, `DISPUTE_NOTE_ADD`, `DISPUTE_EVIDENCE_ADD`, `DISPUTE_CASE_TRANSITION` with outcome, actor
`sub` + username, correlation ID and non-sensitive details; refusals audited in a separate transaction; role denials via
`ACCESS_DENIED`.

### AI boundary
`DisputeQueries` (get, list, active case) is read-only and tool-ready. `DisputeCaseWork` is not exposed in Phase 2.
`DisputeResolutions` is **human-only and must never be a tool**.

### Seed (D9)
The dev/test repeatable seed creates one OPEN CARD_NETWORK case for each of the 33 synthetic DISPUTED payments, linked to
their existing `DISPUTE_OPENED` events, without changing payments or adding ledger events and without deadlines.

## Consequences
- Disputes and refunds share one lock discipline and one ledger; their invariants are checked in one place each.
- The ledger can still receive dispute events from a (simulated) network feed without a case; the investigation summary
  then reports `DISPUTE_CASE_MISSING`. Reconciling a feed outcome into an open case is future work.
- A case opened in error must be withdrawn by a supervisor; there is no delete.
