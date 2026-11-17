# ADR 0009 — Deterministic investigation summary and audited investigation reads

**Status:** Accepted (Phase 2, milestone 2)

## Context
Care executives need a quick, trustworthy answer to "what happened to this payment?". Later phases add
AI assistants, which must ground their answers in verified facts rather than produce plausible guesses.

## Decision
- **Timeline** (`GET /payments/{ref}/timeline`) is served straight from `payment_events`, ordered by
  `occurred_at` then `sequence_no`, with a `recordedOutOfOrder` flag. Descriptions are fixed templates.
- **Investigation summary** (`GET /payments/{ref}/investigation-summary`) is computed by
  `InvestigationSummaryBuilder`, a pure function of (timeline, now): milestones, money totals, dispute state,
  history coverage, and rule-based **findings** with stable codes, severities and cited event sequence numbers.
  No LLM, no free text, no prediction. Thresholds are configuration.
- A new **`investigation` module** owns the summary. It depends on `payment`'s public `PaymentHistory` read API
  and will later also read refund and dispute facts; `payment` does not depend on it.
- **Authorization**: `Roles.INVESTIGATE_PAYMENTS` (CARE_AGENT, CARE_SUPERVISOR and explicitly ADMIN, read-only),
  enforced in URL rules, on controllers and on the services (so non-HTTP callers are checked too).
- **Auditing**: a generic `shared.AuditRecord` event, written synchronously in the read's transaction (fail closed),
  with actor username, immutable subject and correlation ID. `audit_events` became append-only (trigger).
- **Actor resolution**: services take the actor from the security context (`CurrentActor`), never from a parameter.

## Consequences
- Same data → same summary; every finding can be unit-tested and explained by pointing at events.
- Each investigation view costs one audit insert; acceptable for an interactive tool.
- The summary record is the contract future AI tools return; it is versioned (`schemaVersion`).
