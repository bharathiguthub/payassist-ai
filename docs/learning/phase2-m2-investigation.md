# Learning guide — Payment timeline and investigation summary (Phase 2, M2)

Code: `payment/PaymentHistory`, `payment/internal/PaymentHistoryService`, `payment/internal/PaymentDescriptions`,
`investigation/InvestigationService`, `investigation/internal/InvestigationSummaryBuilder`,
`audit/internal/AuditRecordListener`, `identity/CurrentActor`. API: [docs/api.md](../api.md).
Decision record: [ADR 0009](../adr/0009-deterministic-investigation-summary.md).

## 1. Business problem
Investigators need to answer "what happened, when, and what should I look at?" in seconds, and they must
be able to *trust* the answer. A summary that sounds confident but is wrong is worse than none: it leads to
wrong refunds and complaints. Every investigation view is also a view of customer financial data, so it must
be recorded for accountability.

## 2. Architecture and internal workflow
```
GET /payments/{ref}/timeline                      GET /payments/{ref}/investigation-summary
  PaymentController (@PreAuthorize)                 InvestigationController (@PreAuthorize)
        │                                                  │
  PaymentHistoryService.viewTimeline  (@PreAuthorize, @Transactional)   InvestigationService.summarize (same)
        │  timeline(ref): payments + payment_events          │  PaymentHistory.timeline(ref)   ← public API of payment
        │                                                    │  InvestigationSummaryBuilder.build(timeline, now)  ← pure
        └─ publish AuditRecord(VIEW_TIMELINE) ──┐            └─ publish AuditRecord(VIEW_INVESTIGATION_SUMMARY)
                                                ▼
                                AuditRecordListener → INSERT audit_events (same transaction)
```
Module dependencies: `investigation → payment (public API) → customer`; `audit` listens to `shared.AuditRecord`.
`payment` never depends on `investigation`, so refund and dispute facts can later be added to the summary
without creating cycles.

## 3. Design patterns used
- **Pure function core, imperative shell**: `InvestigationSummaryBuilder` has no I/O, no clock of its own, no randomness — trivially testable and deterministic. Services do the I/O.
- **Rules as small, named checks** producing **findings with evidence** (event sequence numbers): explainability by construction.
- **Template-based descriptions** (`PaymentDescriptions`) instead of generated text; unknown codes are reported, never guessed.
- **Published language / anti-corruption**: `PaymentHistory` records are the payment module's public read model; the investigation module never sees entities.
- **Domain event for cross-cutting concerns** (`AuditRecord`): any module can audit without depending on `audit`.
- **Versioned response contract** (`schemaVersion`), designed to be an AI tool result.
- **Defence in depth**: URL rule → controller `@PreAuthorize` → service `@PreAuthorize`.

## 4. Transaction consistency and failure scenarios
| Scenario | What happens |
|---|---|
| Audit insert fails (DB down, trigger, constraint) | Exception rolls back the read transaction; the user gets an error, **never** unaudited data (fail closed). |
| Payment does not exist | 404 Problem Details; no audit row (nothing was disclosed). Tested. |
| A refund is recorded while a summary is being built | The summary is built from one read transaction (READ COMMITTED; payment and events read in sequence). `paymentVersion` in the response tells the client which version it saw. |
| Events arrived late | Timeline is ordered by `occurredAt`, ties by `sequenceNo`; late events are flagged and summarised as `EVENTS_RECORDED_OUT_OF_ORDER`. |
| Status and history disagree (bug or manual DB edit) | `STATUS_HISTORY_MISMATCH` WARNING, so the summary surfaces data problems instead of hiding them. |
| Migrated payment with no early history | `HISTORY_INCOMPLETE`; totals explicitly "cover recorded events only". |

## 5. Security implications
- Server-side authorization at three layers; ADMIN is granted read-only investigation explicitly, not by hierarchy.
- The actor is taken from the security context (`CurrentActor`), so no caller can audit under another identity. The immutable `sub` is stored next to the username.
- Data minimisation: the timeline contains no customer data; the summary uses the masked customer summary; actor subjects are not exposed in API responses.
- Correlation IDs are validated before use (log-injection safe) and stored on audit rows for incident forensics.
- `audit_events` is append-only (trigger), with the same owner-role limitation as payment events.
- Deterministic output avoids a class of risks that generated text would introduce (fabricated reasons, leaking data from other payments, prompt injection via free-text fields).

## 6. Interview questions (AI Lead Engineer)
1. Why is a deterministic, rule-based summary preferable to an LLM summary as the *source of truth*? Where could an LLM still add value safely?
2. How would you expose `InvestigationService.summarize` as a Spring AI tool so the model cannot exceed the user's permissions?
3. What does "fail closed" auditing cost, and when (if ever) would you accept asynchronous auditing?
4. How do findings with evidence references help reduce hallucination when a model explains a payment to a customer?
5. How would you version this contract when a new finding type is added? What breaks clients?
6. Why resolve the actor from the security context rather than accept it as a parameter? How does this generalise to agent-to-service calls (token exchange)?
7. How would you test that a summary never contains unmasked personal data, at scale?
8. A supervisor says the summary is "wrong". How do you debug it using correlation IDs, sequence numbers and the audit log?
