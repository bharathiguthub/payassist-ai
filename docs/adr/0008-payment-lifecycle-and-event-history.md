# ADR 0008 — Payment lifecycle state machine and append-only event history

**Status:** Accepted (Phase 2, milestone 1)

## Context
Phase 1 stored only a payment's current status. Investigating a payment needs to know *what happened
and when*, refunds and disputes must change status safely under concurrency, and history must be
trustworthy enough to show to supervisors, auditors and (later) AI agents.

## Decision
- **State machine as data** (`PaymentTransitions`): a table of allowed `(status, event)` pairs with fixed
  targets; two targets depend on history (cumulative refund amount; status before a dispute). Terminal
  statuses: FAILED, VOIDED, REFUNDED, CHARGED_BACK. Method-specific rule: bank transfers cannot be voided.
- **Event history** in `payment_events`: append-only, enforced by a database trigger (UPDATE/DELETE/TRUNCATE
  rejected) and `@Immutable` on the entity. Corrections are new events.
- **Single write path**: `PaymentLedger` (public interface of the payment module). It locks the payment row
  (JPA `PESSIMISTIC_WRITE`, a PostgreSQL row lock held to the end of the transaction), checks an optional expected version, validates the transition and amounts,
  appends the event with the next gap-free `sequence_no`, and updates the payment (bumping `version`).
- **Event kinds**: lifecycle (changes status), informational (timeline-only workflow facts), system-only
  (`CREATED`, `LEGACY_SNAPSHOT`, never recordable through the ledger).
- **Provenance** on every event: actor subject + username, source (`PROCESSOR`, `CARD_NETWORK`,
  `PAYASSIST`, `MIGRATION`), correlation ID, `occurred_at` (business time) and `recorded_at` (system time).
- **Backward-compatible migration**: status CHECK widened; `version` added with default 0; one
  `LEGACY_SNAPSHOT` per pre-existing payment so every payment has history.
- **Money**: `BigDecimal` + ISO 4217; amounts beyond the currency's minor units are rejected, never rounded.

## Alternatives considered
- *Full event sourcing (status derived only from events)*: more invasive for Phase 1 code and queries;
  the hybrid (state row + event log written in one transaction) gives the same audit value.
- *Optimistic locking only*: invariants that span rows (sum of refunds ≤ amount) would need retries and
  are easy to get wrong; a short row lock serialises writers for one payment only.
- *Status enum with `canTransitionTo`*: harder to see and test as a whole than one table.

## Consequences
- History cannot be rewritten by application code or ordinary SQL. **Limitation:** the trigger can be disabled
  by the table owner, and today the application connects as the owner. Production hardening (later phase):
  run Flyway as an owner role and the application as a separate role with only INSERT/SELECT on
  `payment_events` and `audit_events`.
- Writers to the same payment are serialised; contention is per payment and the lock is held for one short transaction.
- Upgraded databases show a `LEGACY_SNAPSHOT` and an incomplete-history notice; fresh databases have full synthetic history.
