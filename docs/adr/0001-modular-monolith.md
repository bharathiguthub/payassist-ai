# ADR 0001 — Modular monolith backend

**Status:** Accepted (Phase 1)

## Context
PayAssist will grow to cover payments, refunds, policy RAG and AI agents. The domain boundaries are
still being discovered, and the team is small.

## Decision
One Spring Boot application split into application modules (`identity`, `customer`, `payment`,
`audit`, `shared`), each a top-level package under `com.payassist`. Spring Modulith's
`ApplicationModules.verify()` runs as a unit test and fails the build on:
- access to another module's sub-packages (internals), or
- cyclic module dependencies.

Modules interact via public services (`CustomerDirectory`) or domain events (`PaymentViewedEvent`).
Entities never hold JPA relationships across modules.

## Alternatives considered
- **Microservices from day one** — independent deployability, but network calls, distributed
  transactions, and more infrastructure, before we know where the seams really are.
- **Unstructured monolith** — fastest start, but boundaries erode and later extraction becomes costly.

## Consequences
- One deployable and one database transaction scope keep Phase 1 simple.
- Any module can later be extracted into a service: its public API and events are already explicit.
- The Python agent service (LangGraph) will be a separate deployable because it uses a different
  runtime. That is an intentional exception, not a split of the domain.
