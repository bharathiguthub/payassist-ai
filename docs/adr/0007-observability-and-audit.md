# ADR 0007 — Correlation IDs and audit trail

**Status:** Accepted (Phase 1)

## Decision
- `CorrelationIdFilter` (runs first) accepts a well-formed `X-Correlation-Id` or generates one, puts
  it in the logging MDC (`[cid=...]` in every log line) and echoes it in the response. Inbound values
  are validated against `^[A-Za-z0-9-]{8,64}$` to prevent log injection.
- Viewing payment details publishes `PaymentViewedEvent`. The `audit` module writes an
  `audit_events` row **synchronously, in the same transaction**. If auditing fails, the data is not
  returned (fail closed).
- Errors use RFC 9457 Problem Details; stack traces and exception messages are never returned.

## Consequences
- Later phases propagate the correlation ID to the agent service, Kafka headers and Langfuse traces,
  so one ID follows an investigation end to end.
- At higher volume, the audit write moves to an outbox and Kafka without touching the payment module.
