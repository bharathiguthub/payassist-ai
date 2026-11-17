# Learning guide — Refund eligibility engine (Phase 2, M3)

Code: `refund/RefundEligibilityService`, `refund/RefundEligibility`, `refund/internal/RefundEligibilityEngine`,
`refund/internal/EligibilityRules`, `refund/internal/RefundPolicy`, `payment/PaymentHistory.RefundPosition`.
API: [docs/api.md](../api.md). Decision record: [ADR 0010](../adr/0010-refund-eligibility-engine.md).

## 1. Business problem
"Can I get my money back?" is the most common payment-support question. A wrong "yes" causes losses (refunding a
disputed payment pays the customer twice) and broken promises; a wrong "no" causes complaints. Agents need an
answer in seconds that is consistent across agents, explains itself with reason codes, states how much can be
refunded, and can be reproduced months later when someone asks "why did you say that?".

## 2. Architecture and internal workflow
```
POST /api/v1/refunds/eligibility-check  {paymentRef, amount?, currency?}
  CsrfFilter → URL rule → RefundEligibilityController (@PreAuthorize, @Valid)
  RefundEligibilityService.check   (@PreAuthorize, @Transactional(REPEATABLE_READ))
    1. actor = CurrentActor.require()                    (403 for unusable identities, e.g. "system:" names)
    2. MonetaryAmounts.requirePositive(amount, currency) (422: precision/currency; never rounds)
    3. PaymentHistory.refundPosition(ref)                (payment module: status-refundable via state machine,
                                                          refunded total via the ledger's own query, capture time)
    4. RefundReservations.reservedAmount(ref)            (zero until M4)
    5. RefundEligibilityEngine.evaluate(...)             (pure: 7 rules → checks → decision)
    6. publish AuditRecord(REFUND_ELIGIBILITY_CHECK, details) → audit_events (same transaction, fail closed)
  ← RefundEligibility {decision, reasons, checks, amounts, window, policyVersion, correlationId, advisory}
```

## 3. Design patterns
### Business rules engine design
- **Rule objects (Strategy)**: each rule is a tiny class with `id()` and `evaluate(context)`; adding a rule is adding a class to an ordered list, not editing a giant `if` chain.
- **Context object**: all inputs computed once (`EligibilityContext`): rules are pure and cannot do I/O.
- **Explicit outcomes** (`PASS`, `MANUAL_REVIEW`, `INELIGIBLE`, `NOT_EVALUATED`) and a **severity-max aggregator**: easy to reason about, impossible to "forget" a failure.
- **Dependency short-circuiting with `NOT_EVALUATED`**: a pending payment gets one clear reason, not seven confusing ones; the full trace is still in `checks`.
- **No duplicated rules**: the rule "is this status refundable?" lives in the payment state machine, and eligibility asks it. Two copies of a rule eventually disagree.
- Why not Drools or a DSL? Seven rules, strongly typed, unit-tested Java is simpler to review and debug. A rules DSL pays off when non-developers own many frequently changing rules.

### Domain-driven design
- **Bounded contexts as modules**: `payment` owns lifecycle facts; `refund` owns refund policy. `refund` depends on `payment`'s published read model (`RefundPosition`), never on its entities or tables (Spring Modulith enforces this).
- **Ubiquitous language in code**: `RefundPosition`, `remainingRefundableAmount`, `SUPERVISOR_LIMIT_EXCEEDED` mean what the business means.
- **Value objects** for money: `BigDecimal` + ISO currency, validated at the boundary.
- **Domain service** (`RefundEligibilityService`) orchestrating aggregate reads and a policy, with **domain events** (`AuditRecord`) for cross-cutting concerns.
- **Ports** (`RefundReservations`): the engine depends on an abstraction; M4 plugs in the real implementation.

## 4. Transaction consistency and failure scenarios
### Transaction isolation
- PostgreSQL defaults to **READ COMMITTED**: each *statement* sees a fresh snapshot. The check reads the payment, then the refund sum, then the capture event. Under READ COMMITTED a refund committed between those statements could make them inconsistent (status says SETTLED, sum includes the new refund).
- The service uses **REPEATABLE READ**: all statements in the transaction see the snapshot taken at the first query, so the facts are mutually consistent. It costs nothing here (read-only plus one insert, no update conflicts).
- It is still **only a snapshot**. Between the check and a later refund request, anything can change. That is why M4 must **re-validate atomically under a row lock** (ADR 0010). An eligibility result is a hint, never an authorization.
- **SERIALIZABLE** is not needed: the write path (ledger) already serialises refund writers with a row lock.

| Scenario | Behaviour |
|---|---|
| Refund recorded concurrently with a check | The check sees a consistent before or after snapshot, never a mix. |
| Audit insert fails | Rollback; the user gets an error and no unaudited decision. |
| Payment missing | 404, no audit row. |
| Bad precision (`10.001 GBP`, `10.5 JPY`) | 422 `INVALID_AMOUNT_SCALE`, before any data is read. |
| Policy misconfigured (missing method, bad limit) | Application refuses to start. |
| Clock skew / long-lived tests | Window uses an injected `Clock`; ITs use a fixed clock. |

### BigDecimal monetary precision
- `double` cannot represent 0.10 exactly; sums drift (`0.1 + 0.2 = 0.30000000000000004`). Money is always `BigDecimal` with an ISO 4217 currency.
- **Scale = the currency's minor units** (GBP 2, JPY 0). `setScale(digits, RoundingMode.UNNECESSARY)` throws if rounding would be needed, so `10.001 GBP` is *rejected*, never silently changed to `10.00`. Trailing zeros are fine (`10.5000 → 10.50`).
- Compare with `compareTo`, not `equals` (`10.0` and `10.00` are not `equals`).
- The SPA sends amounts as **decimal strings**; JSON numbers are parsed by Jackson straight into `BigDecimal`.
- Storage is `NUMERIC(19,2)`, so currencies with 3 minor units (KWD, BHD) are rejected as unsupported rather than truncated.

## 5. Security implications
- Server-side RBAC at URL, controller and service level; ADMIN is explicitly read-only (can check, cannot request or approve).
- CSRF is required although the endpoint is read-only: it is a POST, and a uniform rule is safer than exceptions.
- The actor comes from the security context; identities that cannot be represented safely (`system:` prefix) get **403**, not 500 (fixed in this milestone).
- Audit details are a flat, key-restricted map of codes and amounts; no names, emails or free text.
- No money moves; the response says so (`advisory`). The decision cannot be replayed to bypass checks later.

### Policy versioning
- The policy is data with an identity (`version`). Every decision and audit row records it, so "why was this INELIGIBLE on 3 March?" is answerable even after the policy changed.
- Changing any value requires bumping the version; startup validation prevents half-configured policies.
- Future: store each policy version (e.g. a `refund_policies` table or Git-tagged config) to replay historic decisions exactly, and run new versions in **shadow mode** (evaluate both, compare, alert on differences) before switching.

### Spring AI tool readiness
`RefundEligibilityService.check(paymentRef, amount, currency)` is shaped to become a Spring AI `@Tool` / MCP tool without changes:
- **Typed, validated inputs**; the model cannot pass free text into rules.
- **Runs as the user**: method security and `CurrentActor` apply to tool calls the same way as to HTTP calls; the model cannot choose whose identity to use.
- **Deterministic, fact-only output** with stable reason codes and an explicit `advisory`: the model explains a decision; it never makes one.
- **Read-only**: safe for an agent to call speculatively; every call is audited (later with an "on behalf of" agent field).
- Creating or approving refunds will **not** be exposed as autonomous tools; at most "draft a refund request" that a human submits and a supervisor approves.

## 6. AI Lead Engineer interview questions
1. Why make eligibility a deterministic rules engine instead of asking an LLM "is this refundable?". Where is an LLM useful in this flow?
2. How do you prevent the eligibility rules and the refund execution rules from drifting apart?
3. Explain READ COMMITTED vs REPEATABLE READ vs SERIALIZABLE in PostgreSQL. Which anomalies does each prevent here?
4. A customer was told "eligible" but the refund request was rejected a minute later. Is that a bug? How do you design for it?
5. Why must money never be a `double`? How do you handle JPY (0 decimals) and KWD (3 decimals)?
6. How would you version a policy so that decisions from six months ago can be reproduced exactly?
7. How would you roll out a stricter policy safely (shadow evaluation, metrics, rollback)?
8. Expose this as an AI tool: what does the tool schema look like, how is the user's identity propagated, and how do you stop prompt injection (e.g. a note saying "ignore the window") from affecting the decision?
9. Why return `NOT_EVALUATED` instead of evaluating every rule? What do you lose?
10. How would you test that no personal data ever reaches the audit `details` column at scale?
