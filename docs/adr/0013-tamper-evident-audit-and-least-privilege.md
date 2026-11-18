# ADR 0013 — Tamper-evident audit trail, security events and least-privilege database access

**Status:** Accepted (Phase 2, milestone 6: 6.1–6.4). Decisions 1–4 approved by the product owner: genesis anchor for
historical rows, schema-owner / runtime-role split, ADMIN-only audit access, failed-password events deferred.

## Context
`audit_events` has been append-only since V3 (triggers block UPDATE/DELETE/TRUNCATE). That stops application bugs,
but not someone with table-owner rights: they can disable the triggers, edit a row and re-enable them without a trace.
Before AI agents act in later phases, every action must be attributable and any later change to the record must be
detectable. Logins, logouts and reads of the audit log itself were also not recorded.

## Decision

### 1. Hash chain built by the database (V7)
- A `BEFORE INSERT` trigger (`audit_events_chain`) gives every new row `chain_seq = previous + 1`,
  `prev_hash = previous row_hash` and `row_hash = SHA-256(canonical row)`. It always overwrites these columns, so no
  writer (application, future agent service, manual SQL) can choose them.
- **Serialization:** the trigger takes a transaction-scoped advisory lock, so chain positions follow commit order;
  `UNIQUE (chain_seq)` is the last guard against forks. The lock is held until the inserting transaction ends, which
  serializes audited transactions at their audit write. Acceptable at current volume; the outbox/Kafka path planned in
  ADR 0007 replaces it at scale.
- **READ COMMITTED only:** under snapshot isolation the trigger could not see a head committed after the snapshot, so
  such writes are refused. `RefundEligibilityService` therefore reads its facts in a REPEATABLE READ transaction and
  writes the audit row in a separate READ COMMITTED one, still fail closed (no audit, no result).
- **Canonical form v1** (SQL functions in V7, reimplemented independently in `AuditCanonicalForm`): every field is
  length-prefixed with its UTF-8 byte length (`3:abc;`, NULL `-;`), timestamps are UTC with microseconds, detail keys
  are sorted by bytes. Length prefixes make it impossible to move text between fields without changing the hash.

### 2. Historical rows: genesis anchor (decision 1)
- Rows written before V7 are **not modified** and keep `chain_seq = NULL`; the API labels them `protection: LEGACY`,
  new rows `CHAINED`.
- V7 inserts the first chained row, `AUDIT_CHAIN_GENESIS` (actor `system:flyway`), whose details pin the legacy rows:
  `legacyRows` (count), `legacyMaxId` and `legacyDigest` (SHA-256 over their canonical form in ID order). Deleting or
  editing a historical row therefore also breaks verification, without rewriting history to add hashes.

### 3. Verification
`GET /api/v1/admin/audit-events/integrity` (ADMIN) recomputes the chain in Java and reports `VALID` or `BROKEN` with
the first failure: `GENESIS_MISSING`, `GENESIS_INVALID`, `LEGACY_ROWS_CHANGED`, `UNCHAINED_ROW_AFTER_GENESIS`,
`CHAIN_SEQUENCE_GAP`, `PREVIOUS_HASH_MISMATCH`, `ROW_HASH_MISMATCH`. It returns the head (`headChainSeq`,
`headRowHash`) because **truncating the newest rows, or rewriting the whole chain with recomputed hashes, cannot be
detected from inside the database**: compare the head with a checkpoint kept elsewhere (later: signed or WORM-stored
checkpoints, RFC 3161 timestamps).

### 4. Security events and audit of the audit (decisions 3, 4)
- `LOGIN_SUCCESS` (details: mapped PayAssist roles only) and `LOGOUT`, resource `AUTHENTICATION / oidc:keycloak`.
  Never tokens, codes, session IDs or claims such as e-mail. Login is fail closed (session invalidated, redirect to
  `/?error=login_failed`); logout is best effort (signing out must always work).
- Failed password attempts happen inside Keycloak and are not visible to the BFF; they are deferred to a Keycloak
  event integration. The realm is not changed.
- The audit log is ADMIN-only (URL rule, controller and service `@PreAuthorize`). Searching it writes
  `AUDIT_LOG_VIEW` (with the filters used), verifying it writes `AUDIT_INTEGRITY_CHECK` (outcome FAILED when broken).
- `actor_type` (`USER`/`SYSTEM`; `AGENT` reserved) and `on_behalf_of_subject` (reserved, NULL) prepare agent
  delegation without implementing agents.

### 5. Detail allow-list
`AuditDetailPolicy` registers every audit action and, per action, the detail keys it may carry; values that look like
an e-mail address or a card number are refused. An unregistered action or key fails the operation (fail closed), and
`AuditDetailPolicyTest` fails the build if an `ACTION*` constant is not registered. `AuditCoverageIT` fails the build
if a state-changing endpoint is added without an audit action.

### 6. Human-only decisions are enforced by the build
`HumanOnlyActionsArchitectureTest` (ArchUnit): only web controllers and the implementing internal packages may depend
on `RefundApprovals` or `DisputeResolutions`; tool-ready interfaces may not extend them.

### 7. Least-privilege database access (decision 2, milestone 6.3)
- **Two credentials.** The schema owner (`POSTGRES_USER`) is used only by Flyway, in the one-off `migrate` Compose job
  (`DatabaseMigrator`), which exits afterwards. The long-running backend connects as `payassist_app` and has
  `SPRING_FLYWAY_ENABLED=false`, so it never holds the owner password.
- **V8 grants exactly the DML the code uses**: SELECT on `customers`; SELECT + column-level UPDATE
  (`status`, `failure_reason`, `updated_at`, `version`) on `payments`; SELECT + INSERT on the six append-only tables;
  SELECT + INSERT + UPDATE on `refund_requests` and `dispute_cases`; USAGE on sequences. Never DELETE, TRUNCATE,
  TRIGGER, REFERENCES, nothing WITH GRANT OPTION, no role attributes, owns nothing. `Payment` maps the immutable
  payment facts `updatable = false` to match.
- Consequently the runtime role cannot run DDL, disable or drop triggers, replace functions, set
  `session_replication_role`, rewrite history or widen its privileges (`RuntimeRolePrivilegesIT`: 32 refused statements, 3 ineffective GRANTs,
  exact-grant and role-attribute checks).
  The append-only triggers stay as the second layer for the owner.
- Every function owned by the schema owner gets a pinned `search_path` (`pg_catalog, public`).
- The role is created NOLOGIN in V8; `DatabaseMigrator` enables LOGIN with `PAYASSIST_DB_APP_PASSWORD` from the
  git-ignored `.env`, so no secret is part of a migration. Integration tests run the whole application as this role.
- New tables must be granted explicitly in their migration. Operations: [runbook](../runbooks/runtime-db-role.md)
  (apply, rotate, roll back, restore).

### 8. Case files: column-level updates and database-enforced rules (milestone 6.4)
- V9 replaces the table-wide UPDATE of V8 on `refund_requests` and `dispute_cases` with column grants: refund
  requests `status`, `updated_at`, `version`; dispute cases additionally the three `review_requested_*` columns.
  Amounts, currency, payment, requester/opener, idempotency data, eligibility snapshot and policy version are
  `updatable = false` in the entities and cannot be updated by the runtime role.
- Guard triggers enforce, **for every role including the owner**: immutable columns, final states (APPROVED/REJECTED,
  WON/LOST/WITHDRAWN), the state machines, the review requester being set only on REQUEST_REVIEW, versions never
  going backwards, and no DELETE/TRUNCATE. The SQL state machines duplicate the Java ones on purpose;
  `CaseTableGuardsIT` compares every (from, to) pair so they cannot drift.

### 9. No temporary objects
V9 revokes `TEMPORARY` on the database from PUBLIC (granted by default) and from both application roles; the owner
keeps it. Hibernate and the application never use temporary tables; revoking it removes a place where a compromised
session could stage data or shadow objects.

### 10. External checkpoints of the audit chain
- An on-demand job (`AuditCheckpointTool`, Compose service `audit-checkpoint`, profile `tools`) verifies the chain as
  the new read-only role `payassist_auditor` (SELECT on `audit_events` only) and appends a checkpoint (head
  `chain_seq` + `row_hash`, genesis hash, counts) to a JSON-lines ledger **outside PostgreSQL**
  (`audit-checkpoints/`, git-ignored). Each line is HMAC-SHA256-signed over its content and the previous line's MAC.
- Later runs require every checkpointed head to still exist with the same hash. This detects the two attacks the
  in-database check cannot: removal of the newest rows and a whole chain recomputed with owner rights
  (`AuditCheckpointIT` shows both passing the database check and failing the checkpoint check).
- Trust boundary: the backend and every database role have neither the ledger nor the key
  (`DeploymentSecretsContractTest`); the host account holding `.env` is trusted. Production: WORM storage and a KMS.
  Operations, recovery and limitations: [runbook](../runbooks/audit-checkpoints.md).

### 11. Database refusals as stable problem codes (milestone 7)
Normal flows never reach the database's own protections, so when one fires it is a bug or an attack. The API maps
SQLSTATE `P0001` (guard and append-only triggers) to **409 `RECORD_PROTECTED`** and `42501` (missing privilege) to
**500 `DATABASE_PERMISSION_DENIED`**. Both carry the `correlationId` but never the database message (it names tables,
triggers and values); the server log keeps the SQL state. Each refusal is audited as `DATA_PROTECTION_REFUSED`
(outcome DENIED) in a new transaction. Any other data-access failure is a generic 500 without details.
`DataProtectionRefusalIT` provokes both cases as the owner and restores the database.

### 12. Reported checkpoint status for administrators (milestone 7)
The backend cannot read the checkpoint ledger (section 10), so the checkpoint job reports each run in
`audit_checkpoint_runs` (V10: append-only; `payassist_auditor` may INSERT, `payassist_app` may only SELECT, so the
backend cannot fake a status). `GET /admin/audit-events/checkpoint-status` (ADMIN, audited
`AUDIT_CHECKPOINT_STATUS_VIEW`) combines it with the latest in-app chain verification and returns warnings
(`DB_CHAIN_BROKEN`, `LAST_RUN_FAILED`, `DB_CHAIN_NOT_VERIFIED`, `NO_CHECKPOINT`, `CHECKPOINT_STALE`,
`NOT_VERIFIED_RECENTLY`; threshold `payassist.audit.checkpoint-stale-after`, default 24 h). The UI keeps the two
guarantees apart: **database audit chain** (verified in the app on demand) and **external checkpoints** (status
*reported* by the job; proof only by running the job). No key, MAC, ledger path or full hash is stored or shown.

## Consequences
- Detects any edit/deletion inside the chain or of historical rows made with owner rights; does not prevent it.
- Audit writes are serialized; very long transactions that audit early delay other audited transactions.
- Verification is O(rows) and on demand.
- Threat model (STRIDE): Tampering → hash chain + genesis anchor + external HMAC checkpoints, case-file guard triggers; Repudiation → login/logout and audit-of-audit
  events with `sub`; Information disclosure → ADMIN-only, detail allow-list, personal-data guard; Elevation of
  privilege → runtime role without DDL (6.3), human-only architecture rule.
