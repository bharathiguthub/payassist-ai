# Phase 2 · M6 — Tamper-evident audit and security events

Decision record: [ADR 0013](../adr/0013-tamper-evident-audit-and-least-privilege.md). API: [api.md](../api.md#audit-trail-admin-only-milestone-6-adr-0013).

## 1. Append-only is not tamper-evident
Since V3, triggers refuse UPDATE/DELETE/TRUNCATE on `audit_events`. That protects against *bugs*. Anyone with the
table owner's rights can still run `ALTER TABLE audit_events DISABLE TRIGGER ...`, change a row and re-enable the
trigger. Nothing in the data shows it happened. **Tamper-evidence** means such a change becomes detectable later.

## 2. The hash chain
```
row n:   row_hash(n) = SHA-256( canonical(row n, including prev_hash = row_hash(n-1)) )
genesis: prev_hash = 000…000
```
Change one byte of row 5 and `row_hash(5)` no longer matches; delete row 5 and row 6's `chain_seq`/`prev_hash` no
longer fit. `AuditChainIT` demonstrates each case against real PostgreSQL.

Things worth noticing in `V7__audit_integrity.sql` and `AuditCanonicalForm`:
- **Canonical form** — hashing needs one exact byte representation. Every field is length-prefixed (`3:abc;`), so
  `"a;" + "b"` and `"a" + ";b"` cannot collide. Timestamps are UTC with microseconds (PostgreSQL's precision).
- **The database builds the chain** (a trigger), so every writer is covered, including future services in other
  languages. The trigger overwrites `chain_seq`/`prev_hash`/`row_hash`: a writer cannot pick them.
- **Two implementations** — SQL computes, Java verifies. If someone replaces the SQL function, Java disagrees.
- **Serialization** — the chain is a single linked list, so inserts take an advisory lock. Under REPEATABLE READ a
  transaction would not see the newest head, so the trigger refuses it. That is why the refund eligibility check
  now audits in a separate READ COMMITTED transaction.

## 3. History you cannot rewrite: the genesis anchor
Rows written before V7 were never hashed. Backfilling hashes would mean *updating* an append-only table. Instead the
first chained row, `AUDIT_CHAIN_GENESIS`, records how many legacy rows exist, the highest legacy ID and a digest over
their content. Legacy rows stay byte-for-byte unchanged (`protection: LEGACY` in the API), yet editing or deleting
one breaks verification (`LEGACY_ROWS_CHANGED`).

## 4. What a hash chain cannot do
- It **detects**, it does not **prevent**: prevention is milestone 6.3 (runtime role without owner rights).
- Deleting the **newest** rows, or recomputing the **whole** chain, leaves a valid-looking chain. Defence: keep the
  head (`headChainSeq`, `headRowHash`) outside the database: a printed report, a ticket, later a signed or WORM-stored
  checkpoint (S3 Object Lock, RFC 3161 timestamping).

## 5. Who watches the watchers
Reading the audit log is itself sensitive (it reveals who looked at which customer). `AUDIT_LOG_VIEW` records each
search with its filters; `AUDIT_INTEGRITY_CHECK` records each verification. Filters that contain a card number or an
e-mail are refused *before* they could be written into the permanent trail.

## 6. Security events without secrets
`LOGIN_SUCCESS` stores the OIDC `sub`, username and mapped roles: never tokens, authorization codes, session IDs or
claims like e-mail. Login is **fail closed** (no audit row → session invalidated); logout is **best effort** (a user
must always be able to sign out). Wrong passwords happen inside Keycloak, which has its own event store (deferred).

## 7. Guardrails that run at build time
| Test | Fails the build when… |
|---|---|
| `AuditDetailPolicyTest` | an `ACTION*` constant is not registered in `AuditDetailPolicy` |
| `AuditCoverageIT` | a POST/PUT/PATCH/DELETE endpoint has no declared audit action |
| `HumanOnlyActionsArchitectureTest` | anything but a web controller uses `RefundApprovals` / `DisputeResolutions` |

## 8. AI lead engineer takeaways
- **Non-repudiation before autonomy.** An agent acting at machine speed must leave records that are attributable
  (`actor_type`, reserved `on_behalf_of_subject`) and tamper-evident *before* it gets tools.
- **Excessive agency (OWASP LLM06)** is handled by architecture, not prompts: human-only decisions are unreachable
  from tool code, and the build proves it.
- **Deterministic guardrails outside the model**: detail allow-lists, personal-data guards and hash verification do
  not depend on model behaviour.
- **Audit data is evaluation data**: structured, correlated rows (`correlation_id`) later join Langfuse traces to
  reconstruct what an agent did and why.

## 9. Least privilege in the database (M6.3)
Detection (the chain) is paired with **prevention**: the backend no longer connects as the schema owner.
- **Two identities**: the owner migrates (one-off `migrate` job) and leaves; the long-running app is `payassist_app`.
  An attacker who takes over the app process gets only the runtime role's password.
- **Grant what the code uses, nothing else** (V8): SELECT/INSERT on history, column-level UPDATE on `payments`
  (status only moves; amounts never change), no DELETE/TRUNCATE anywhere, nothing `WITH GRANT OPTION`.
- **Defence in depth**: for the app, an audit UPDATE fails with *permission denied* (layer 1); for the owner, the
  append-only trigger refuses it (layer 2); if an owner disables the trigger, the hash chain reveals it (layer 3).
- **Prove it with tests**: `RuntimeRolePrivilegesIT` runs 32 forbidden statements (plus GRANT attempts) as the app role, and every other
  integration test runs as that role, so a missing grant fails the build rather than production.
- AI angle: the same pattern applies to agent tools: each tool gets its own narrowly scoped credential, and the model
  never sees credentials at all.

## 10. Final hardening (M6.4)
- **Column-level privileges** — a refund request's amount, requester and policy version never change after creation,
  so the runtime role cannot update them at all (`GRANT UPDATE (status, updated_at, version)`), and the JPA entity
  marks them `updatable = false` so Hibernate never tries.
- **Rules that hold even for the owner** — guard triggers keep decided refunds and closed disputes final, enforce the
  state machines and forbid deletes. Duplicating the state machine in SQL is a deliberate trade-off; a test compares
  every (from, to) pair with the Java table so the copies cannot silently diverge.
- **Remove what you do not use** — the TEMPORARY privilege is granted to everyone by default; nothing in PayAssist
  needs it, so application roles lose it.
- **Anchoring outside the system you distrust** — the hash chain cannot detect someone with owner rights who deletes
  the newest rows or recomputes every hash. An external, HMAC-signed checkpoint ledger can: `AuditCheckpointIT`
  forges both attacks; the database check says VALID, the checkpoint check says `CHECKPOINTED_ROW_MISSING` /
  `CHECKPOINT_MISMATCH`. The checkpoint job uses its own read-only role and is the only component with the key.
- **State your trust boundary** — a local ledger protects against database-level tampering, not a compromised host.
  Write-once storage owned by another account (S3 Object Lock, transparency logs, RFC 3161 timestamps) moves the
  boundary further out; see the [checkpoint runbook](../runbooks/audit-checkpoints.md).
- AI angle: when agents act, the same checkpoints let you prove afterwards that the record of what an agent did has
  not been edited by anyone, including the people operating the database.
