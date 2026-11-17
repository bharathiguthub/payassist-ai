# ADR 0010 — Deterministic, versioned refund eligibility engine

**Status:** Accepted (Phase 2, milestone 3)

## Context
Care executives must know, before promising anything to a customer, whether a refund is possible, how much,
and why not. The answer must be explainable, reproducible later (for complaints and audits), identical for
every user and (later) for AI assistants, and must not contradict the rules that apply when money is recorded.

## Decision
- **New `refund` module.** `RefundEligibilityService` (public, permission-checked, audited) →
  `RefundEligibilityEngine` (pure function) → ordered list of small `EligibilityRule`s.
- **Endpoint** `POST /api/v1/refunds/eligibility-check` (POST because it carries an amount; read-only; CSRF
  required like every unsafe method). Amount optional: without it the engine evaluates the full remaining amount.
- **Rules** (evaluation order): `PAYMENT_STATUS`, `CURRENCY`, `PAYMENT_METHOD`, `REFUND_WINDOW`,
  `REFUNDABLE_AMOUNT`, `PENDING_REFUNDS`, `APPROVAL_LIMIT`. Each returns PASS / MANUAL_REVIEW / INELIGIBLE /
  NOT_EVALUATED with a stable `ReasonCode` and a templated message. Decision = most severe outcome.
- **No duplicated rules.** Status refundability comes from the payment state machine
  (`PaymentTransitions.isAllowed(status, REFUND_RECORDED, method)`, exposed as `RefundPosition.refundTransitionAllowed`);
  the refunded total uses the same query the ledger uses; precision uses `MonetaryAmounts`. The engine adds only policy.
- **Policy as validated configuration** (`payassist.refund.policy`, `RefundPolicy`): `version`, supported currencies,
  per-method window/enabled/partial-refund flags, per-currency supervisor approval limits. Invalid or incomplete
  policy fails startup. Every decision and audit record carries `policyVersion`.
- **Supervisor limit** → `MANUAL_REVIEW` + `SUPERVISOR_LIMIT_EXCEEDED` (escalation; see "Refund escalation" below).
- **Money**: request precision beyond the currency's minor units → 422 `INVALID_AMOUNT_SCALE`; currencies with more
  than 2 minor units or unknown codes → 422 `UNSUPPORTED_CURRENCY`. Nothing is rounded.
- **Consistency**: the check runs in one `REPEATABLE READ` transaction so all facts come from one snapshot. It is
  **advisory**; milestone 4 must re-run the engine atomically when creating a refund request (see below).
- **Audit**: `REFUND_ELIGIBILITY_CHECK` with structured, non-sensitive `details` (decision, reason codes, amount,
  currency, remaining amount, policy version, payment version) in a new JSONB column (V4).
- **Reserved amount**: `RefundReservations` port; returned zero in M3. Since M4 the adapter sums pending refund
  requests, and request creation follows the atomic re-validation below ([ADR 0011](0011-refund-requests-and-approval.md)).

## Refund escalation (decided by the product owner)
Amounts above the supervisor limit stay pending with `ESCALATION_REQUIRED` (milestone 4). Supervisors cannot approve
them and ADMIN does not approve them. A future `REFUND_MANAGER` role is documented here but **not implemented**.

## Requirement for milestone 4: atomic re-validation
When a refund request is created, inside one transaction:
1. lock the payment row (`PaymentRepository.findByPaymentRefForUpdate` via a payment-module API);
2. read `RefundPosition` and the reserved amount (pending requests) under that lock;
3. run `RefundEligibilityEngine.evaluate` with the **current** policy;
4. reject `INELIGIBLE`; persist the request with the decision, reason codes and `policyVersion`;
5. commit. A decision obtained earlier from this endpoint is never trusted as input.

## Consequences
- Same facts + same policy version → same decision; decisions are reproducible from the audit record.
- Changing the policy is a configuration change that must bump `version` (reviewed like code).
- The time-dependent window means integration tests use a fixed clock (`FixedClockTestConfig`).
