# PayAssist AI — API reference (`/api/v1`)

All endpoints are served by the Spring Boot BFF on the same origin as the SPA. Authentication is the
`PAYASSIST_SESSION` HttpOnly cookie established by the Keycloak login (see [ADR 0002](adr/0002-bff-authentication.md));
there are no bearer tokens in the browser. Unsafe methods (POST/PUT/PATCH/DELETE) also require the
`X-XSRF-TOKEN` header echoing the `XSRF-TOKEN` cookie.

When the backend runs with the `dev` profile, the generated OpenAPI document is at `/v3/api-docs`
and Swagger UI at `/swagger-ui.html` (login required). This file is the reviewed, human-readable contract.

## Conventions

| Topic | Rule |
|---|---|
| Identifiers | Payments are addressed by their business reference `paymentRef` (`^[A-Za-z0-9-]{1,32}$`, e.g. `PAY-000042`, case-insensitive). Internal UUIDs are never needed in URLs. |
| Money | JSON numbers serialised from `BigDecimal` without rounding, always with an ISO 4217 `currency` field. |
| Time | ISO-8601 UTC instants (`2026-05-18T00:53:00Z`). |
| Errors | RFC 9457 Problem Details (`application/problem+json`). Business-rule errors add a stable `code` property. |
| Correlation | Every response carries `X-Correlation-Id`; a client may send its own (8–64 chars `[A-Za-z0-9-]`). It is stored on audit records and payment events. |
| Auditing | Every read of a single payment's details, timeline or summary, and every eligibility check, writes an `audit_events` row in the same transaction (if auditing fails, the request fails). |
| Identity | Accounts whose username uses the reserved `system:` prefix are refused with 403 on audited operations. |

### Error codes

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | — | Invalid parameter (validation) |
| 401 | — | Not logged in |
| 403 | — | Logged in but role not allowed |
| 404 | — | Resource not found |
| 409 | `INVALID_STATE_TRANSITION` | The payment's lifecycle does not allow the action in its current status |
| 409 | `CONCURRENT_MODIFICATION` | Another request changed the resource first; reload and retry |
| 422 | e.g. `INVALID_AMOUNT_SCALE`, `UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT`, `CURRENCY_REQUIRED`, `REFUND_EXCEEDS_PAYMENT_AMOUNT` | Business rule violated (malformed money is rejected, never rounded) |

## Access matrix

| Endpoint | CARE_AGENT | CARE_SUPERVISOR | ADMIN | no role |
|---|:-:|:-:|:-:|:-:|
| `GET /me` | ✔ | ✔ | ✔ | ✔ |
| `GET /payments` | ✔ | ✔ | ✔ | 403 |
| `GET /payments/{ref}` | ✔ | ✔ | ✔ | 403 |
| `GET /payments/{ref}/timeline` | ✔ | ✔ | ✔ read-only | 403 |
| `GET /payments/{ref}/investigation-summary` | ✔ | ✔ | ✔ read-only | 403 |
| `POST /refunds/eligibility-check` | ✔ | ✔ | ✔ read-only | 403 |
| `GET /payments/stats` | 403 | ✔ | ✔ | 403 |
| `GET /admin/audit-events` | 403 | 403 | ✔ | 403 |

Enforced twice: URL rules in `SecurityConfig` and `@PreAuthorize` on controllers **and** services.

---

## Phase 1 endpoints

- `GET /me` — current user and PayAssist roles (never tokens).
- `GET /payments?q=&status=&page=&size=` — search by payment/customer reference prefix and status; paged.
- `GET /payments/{paymentRef}` — details with masked customer email. Audited as `VIEW`.
- `GET /payments/stats` — counts per status.
- `GET /admin/audit-events?page=&size=` — audit trail, newest first. Phase 2 adds `correlationId` and a
  `details` object (non-sensitive codes, amounts and versions; e.g. eligibility decision and `policyVersion`).

## Phase 2 endpoints

### `GET /payments/{paymentRef}/timeline`

Chronological history from `payment_events`, the authoritative record. Ordered by `occurredAt`, ties broken by
`sequenceNo` (recording order). Contains no customer data. Audited as `VIEW_TIMELINE`.

```json
{
  "paymentRef": "PAY-000042",
  "currentStatus": "SETTLED",
  "currentStatusDescription": "Funds settled to the merchant.",
  "amount": 1234.50,
  "currency": "GBP",
  "eventCount": 4,
  "events": [
    {
      "sequenceNo": 1,
      "eventType": "CREATED",
      "kind": "SYSTEM_ONLY",
      "description": "Payment created at the processor for 1234.50 GBP.",
      "fromStatus": null,
      "toStatus": "PENDING",
      "amount": 1234.50,
      "currency": "GBP",
      "reasonCode": null,
      "reasonDescription": null,
      "actor": "system:processor-simulator",
      "source": "PROCESSOR",
      "correlationId": "seed-pay-000042",
      "occurredAt": "2026-03-12T10:00:00Z",
      "recordedAt": "2026-11-16T21:18:20Z",
      "recordedOutOfOrder": false
    }
  ]
}
```

| Field | Notes |
|---|---|
| `kind` | `LIFECYCLE` (changes status), `INFORMATIONAL` (workflow fact, `toStatus` is null), `SYSTEM_ONLY` (`CREATED`, `LEGACY_SNAPSHOT`) |
| `description` | Fixed template filled with recorded values. Never generated text. |
| `reasonDescription` | From a fixed glossary; unknown codes read "Reason code X (no description on file)." |
| `actor` | Username, or `system:<name>` for processor, card network and migration events. Immutable user IDs are not exposed. |
| `source` | `PROCESSOR`, `CARD_NETWORK`, `PAYASSIST`, `MIGRATION` |
| `recordedOutOfOrder` | `true` when the event was recorded after an event that occurred later (late message, or history appended after a migration snapshot). |

### `GET /payments/{paymentRef}/investigation-summary`

Deterministic summary computed from the same recorded data. Audited as `VIEW_INVESTIGATION_SUMMARY`.
`schemaVersion` is `1.0`; fields are only added within a schema version.

```json
{
  "schemaVersion": "1.0",
  "paymentRef": "PAY-000011",
  "generatedAt": "2026-11-16T12:00:00Z",
  "paymentVersion": 0,
  "basis": "Derived only from recorded payment data (payments and payment_events). No inference or prediction.",
  "payment": { "amount": 87.11, "currency": "USD", "status": "FAILED",
               "statusDescription": "Payment failed; no funds were captured.", "terminal": true,
               "paymentMethod": "WALLET", "merchantName": "Example Streaming Service",
               "failureReason": "ISSUER_DECLINED", "failureDescription": "The card issuer declined the payment.",
               "createdAt": "...", "lastUpdatedAt": "...",
               "customer": { "customerRef": "CUS-00012", "fullName": "...", "maskedEmail": "c***@example.com", "...": "..." } },
  "milestones": { "createdAt": "...", "authorizedAt": null, "capturedAt": null, "settledAt": null,
                  "failedAt": "...", "voidedAt": null, "lastRefundAt": null,
                  "disputeOpenedAt": null, "disputeClosedAt": null },
  "money": { "currency": "USD", "paymentAmount": 87.11, "refundedAmount": 0.00,
             "notRefundedAmount": 87.11, "refundCount": 0 },
  "dispute": { "state": "NONE", "reasonCode": null, "reasonDescription": null, "openedAt": null, "closedAt": null },
  "history": { "eventCount": 2, "complete": true, "recordedFrom": "..." },
  "findings": [
    { "code": "PAYMENT_FAILED", "severity": "WARNING",
      "message": "Payment failed with reason ISSUER_DECLINED: The card issuer declined the payment.",
      "evidence": [2] }
  ],
  "possibleNextEvents": []
}
```

**Findings.** Each is rule-based, cites evidence by event `sequenceNo`, and is sorted WARNING first, then by code.

| Code | Severity | Rule |
|---|---|---|
| `PAYMENT_FAILED` | WARNING | Status is FAILED (states the recorded reason, or that none was recorded) |
| `PENDING_LONGER_THAN_EXPECTED` | WARNING | PENDING longer than `payassist.investigation.pending-alert-after` (24h) |
| `AUTHORIZED_NOT_CAPTURED` | WARNING | AUTHORIZED longer than `authorized-alert-after` (7d) |
| `SETTLEMENT_DELAYED` | WARNING | CAPTURED longer than `settlement-delay-after` (3d) |
| `AUTHORIZATION_VOIDED` | INFO | Status is VOIDED |
| `REFUNDS_RECORDED` | INFO | At least one `REFUND_RECORDED` event; totals given |
| `DISPUTE_OPEN` | WARNING | Status is DISPUTED |
| `DISPUTE_WON` | INFO | Last dispute resolved in the merchant's favour |
| `CHARGEBACK_RECORDED` | WARNING | Dispute lost |
| `HISTORY_INCOMPLETE` | INFO | A `LEGACY_SNAPSHOT` exists: history before the migration was not recorded |
| `EVENTS_RECORDED_OUT_OF_ORDER` | INFO | At least one event has `recordedOutOfOrder = true` |
| `STATUS_HISTORY_MISMATCH` | WARNING | The last applied status event does not match the payment's status (data-integrity alarm) |

The thresholds are synthetic configuration, not card-scheme rules. `possibleNextEvents` lists the lifecycle
events the state machine allows from the current status and payment method.

### `POST /refunds/eligibility-check`

Advisory, **read-only** refund eligibility decision from the deterministic rules engine
([ADR 0010](adr/0010-refund-eligibility-engine.md)). Nothing is created, reserved or executed. CSRF token required
(POST). Audited as `REFUND_ELIGIBILITY_CHECK` with details `decision`, `reasonCodes`, `requestedAmount`, `currency`,
`remainingRefundableAmount`, `policyVersion`, `paymentVersion`.

Request:

```json
{ "paymentRef": "PAY-000072", "amount": "25.50", "currency": "GBP" }
```

| Field | Rules |
|---|---|
| `paymentRef` | required, `^[A-Za-z0-9-]{1,32}$` |
| `amount` | optional; JSON number or decimal string. Omit to evaluate the full remaining refundable amount. More decimals than the currency allows → 422 `INVALID_AMOUNT_SCALE` (never rounded). |
| `currency` | ISO 4217 (`^[A-Z]{3}$`); required with `amount`. Unknown, or more than 2 minor units → 422 `UNSUPPORTED_CURRENCY`. |

Response (abridged):

```json
{
  "schemaVersion": "1.0",
  "decision": "MANUAL_REVIEW",
  "paymentRef": "PAY-000024",
  "requestedAmount": 1830.43, "currency": "GBP",
  "paymentStatus": "SETTLED", "paymentMethod": "CARD",
  "paymentAmount": 1830.43, "paymentCurrency": "GBP",
  "previouslyRefundedAmount": 0.00, "reservedPendingAmount": 0.00, "remainingRefundableAmount": 1830.43,
  "refundWindow": { "basis": "CAPTURED_AT", "startsAt": "...", "endsAt": "...", "days": 120, "open": true },
  "reasons": [ { "code": "SUPERVISOR_LIMIT_EXCEEDED", "outcome": "MANUAL_REVIEW",
                 "message": "Requested 1830.43 GBP exceeds the supervisor approval limit of 1000.00 GBP; ..." } ],
  "checks": [ { "rule": "PAYMENT_STATUS", "outcome": "PASS", "code": null, "message": "Status SETTLED allows refunds." } ],
  "policyVersion": "SYNTHETIC-REFUND-POLICY-2026.11-v1",
  "evaluatedAt": "2026-11-16T12:00:00Z",
  "paymentVersion": 0,
  "correlationId": "b0d6...",
  "advisory": "Advisory check only: no refund was created, reserved or executed. Eligibility is re-validated when a refund request is submitted."
}
```

(`checks` contains one entry per rule; abridged above.)

**Decision** = most severe rule outcome: any `INELIGIBLE` → `INELIGIBLE`; else any `MANUAL_REVIEW` → `MANUAL_REVIEW`;
else `ELIGIBLE` (reason `WITHIN_POLICY`). Rules after a failed status check report `NOT_EVALUATED`.

| Rule | Reason codes |
|---|---|
| `PAYMENT_STATUS` (from the payment state machine) | `PAYMENT_NOT_CAPTURED`, `PAYMENT_FAILED`, `PAYMENT_VOIDED`, `PAYMENT_FULLY_REFUNDED`, `PAYMENT_DISPUTED`, `PAYMENT_CHARGED_BACK` → INELIGIBLE |
| `CURRENCY` | `CURRENCY_MISMATCH`, `CURRENCY_NOT_SUPPORTED` → INELIGIBLE |
| `PAYMENT_METHOD` | `PAYMENT_METHOD_NOT_SUPPORTED`, `PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD` (bank transfers: full refunds only) → INELIGIBLE |
| `REFUND_WINDOW` (from first capture; card 120 d, wallet 90 d, bank transfer 60 d) | `REFUND_WINDOW_EXPIRED` → INELIGIBLE |
| `REFUNDABLE_AMOUNT` (amount − refunded − pending) | `NOTHING_LEFT_TO_REFUND`, `AMOUNT_EXCEEDS_REFUNDABLE` → INELIGIBLE |
| `PENDING_REFUNDS` | `PENDING_REFUND_IN_PROGRESS` → MANUAL_REVIEW |
| `APPROVAL_LIMIT` (GBP 1000, USD 1250, EUR 1150, INR 100000) | `SUPERVISOR_LIMIT_EXCEEDED`, `APPROVAL_LIMIT_NOT_CONFIGURED` → MANUAL_REVIEW |

All values are the **synthetic** policy in `application.yml` (`payassist.refund.policy`). Reason codes are stable;
the decision is re-validated atomically when a refund request is created (milestone 4).
