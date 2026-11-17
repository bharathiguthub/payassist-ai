# ADR 0005 — PostgreSQL 17 + pgvector, Flyway, synthetic data

**Status:** Accepted (Phase 1)

## Decision
- **PostgreSQL 17** using the `pgvector/pgvector:0.8.7-pg17` image in Docker Compose **and** in
  Testcontainers, so tests run against the same engine as development. The `vector` extension is
  *available* but not created: the RAG phase adds `CREATE EXTENSION vector` in its own migration
  (`DatabaseIT` asserts both facts).
- **Flyway** owns the schema (`ddl-auto=validate`). Versioned migrations live in `db/migration`.
- **Synthetic data** is a *repeatable* migration in a separate location (`db/seed`), enabled only by
  the `dev` profile and tests. It is deterministic (`md5()`-derived UUIDs), idempotent (`ON CONFLICT DO
  NOTHING`) and uses `example.com` emails and obviously fictitious names and merchants.
- No PAN, CVV or bank account numbers are modelled, which keeps PCI DSS scope out of PayAssist.

## Alternatives considered
- Separate vector database (Pinecone, OpenSearch): another system to secure and operate. pgvector
  keeps relational and vector data transactional and in one place, which is sufficient at our scale.
- Hibernate auto-DDL: not reviewable and unsafe for production.
- Seed data in a versioned migration: it would ship to production.

## Consequences
- Production never loads seed data unless someone explicitly adds the `db/seed` location.
- Local Docker exposes Postgres on host port **5433** by default to avoid clashing with a locally
  installed PostgreSQL.
