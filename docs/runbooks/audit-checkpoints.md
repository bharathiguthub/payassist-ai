# Runbook — external audit-chain checkpoints

Design: [ADR 0013](../adr/0013-tamper-evident-audit-and-least-privilege.md) §10. Code:
`audit/internal/AuditCheckpointTool` (job), `AuditCheckpointLedger` (file format). Compose service `audit-checkpoint`
(profile `tools`, never started by `docker compose up`).

## Why
The hash chain inside PostgreSQL detects edits and gaps, but someone with **owner rights** can delete the newest rows
or recompute every hash after a forged change, and the result is a valid-looking chain (`AuditCheckpointIT` shows both).
A checkpoint records the chain head (`chain_seq`, `row_hash`) **outside the database**; later the head must still be
there, unchanged. Owner rights over PostgreSQL are no longer enough to rewrite history unnoticed.

## Trust boundary
| Component | Has | Does not have |
|---|---|---|
| Backend (`payassist_app`) | runtime DB role | the ledger file, the HMAC key, the auditor password, owner credentials |
| `migrate` job | owner credentials (to enable the auditor's login) | the ledger, the HMAC key |
| `audit-checkpoint` tool | `payassist_auditor` (SELECT on `audit_events` only), the HMAC key, the ledger mount | owner credentials, any write privilege in the database |
| Host account running Docker | `.env` (key) and `audit-checkpoints/` | — (trusted: it can also read every secret) |

The ledger therefore cannot be rewritten by the application or by any database role. Who controls the host account
and `.env` can forge checkpoints: in production the ledger belongs in write-once storage owned by a different
account (S3 Object Lock, a WORM bucket, a transparency log, RFC 3161 timestamps), with the key in a KMS.
`DeploymentSecretsContractTest` keeps the key and the mount away from every other Compose service.

## Ledger format
`audit-checkpoints/audit-checkpoints.jsonl`, one JSON object per line, append-only:
```json
{"seq":3,"createdAt":"2026-11-18T03:30:00Z","headChainSeq":180,"headRowHash":"…64 hex…","chainedRows":180,
 "legacyRows":164,"genesisRowHash":"…","prevMac":"<mac of line 2>","mac":"<HMAC-SHA256>"}
```
`mac = HMAC-SHA256(key, "pac1|seq|createdAt|headChainSeq|headRowHash|chainedRows|legacyRows|genesisRowHash|prevMac")`.
Every line covers the previous line's MAC, so the ledger is a chain of its own.

## Use
```powershell
.\scripts\audit-checkpoint.ps1 -Create   # verify everything, then append a checkpoint (e.g. daily, after a release)
.\scripts\audit-checkpoint.ps1           # verify only
```
Output example: `Audit checkpoint create: OK | checkpoints=3 | head=#180 9f2c41d0a1b2c3d4… | appended`.
Exit codes: `0` OK, `2` integrity failure, `1` configuration error. The key and passwords are never printed.

## Status in the admin UI (milestone 7)
Every run (verify or create) is also recorded in `audit_checkpoint_runs` (time, mode, result, reason code, number of
checkpoints, newest checkpointed chain row, a 16-character hash prefix). The Audit log page shows it under
**External checkpoints — reported**, next to the in-app **Database audit chain** verification, with warnings for a
failed last run, no checkpoint, a checkpoint older than 24 h (`payassist.audit.checkpoint-stale-after`) or no recent
successful verification. The panel is a reported status: the proof is only re-established by running this tool. If a
run cannot write its status row, the tool prints a warning and the panel turns stale.

## What is verified (both modes)
1. **Ledger**: line numbers 1..n, each `prevMac` equals the previous `mac`, every MAC recomputes, heads never go
   backwards. Failures: `LEDGER_MALFORMED_LINE`, `LEDGER_SEQUENCE_BROKEN`, `LEDGER_LINK_BROKEN`,
   `LEDGER_MAC_MISMATCH`, `LEDGER_HEAD_WENT_BACKWARDS`, `LEDGER_UNREADABLE`.
2. **Database chain**: the full verification of the admin integrity endpoint (`DB_CHAIN_<reason>`).
3. **Checkpoints vs database**: the genesis row and every checkpointed head row still exist with the same hash.
   Failures: `CHECKPOINTED_ROW_MISSING` (rows removed), `CHECKPOINT_MISMATCH` (history rewritten),
   `GENESIS_CHANGED`.

`create` appends only when all three pass (fail closed) and never rewrites a line (append mode, `fsync`, an exclusive
lock for the whole run).

## Recovery
- **Any failure is a security incident.** Do not edit the ledger or the audit table, and do not create new
  checkpoints over a failure (the tool refuses anyway). Copy `audit-checkpoints/` and a fresh `pg_dump` as evidence.
- `CHECKPOINTED_ROW_MISSING` / `CHECKPOINT_MISMATCH` / `GENESIS_CHANGED`: the database history changed after the
  checkpoint. Find the last backup whose chain still matches the ledger (restore it into a scratch database and run
  the tool against it), then decide with security which data to restore. The failing checkpoint number brackets the
  time window.
- `LEDGER_*`: the ledger file was changed or damaged. Compare it with your off-host copy. If the copy verifies,
  restore it; otherwise keep the damaged file as evidence, rename it (e.g. `audit-checkpoints.broken-<date>.jsonl`)
  and start a new ledger with `-Create`. Record the decision: protection restarts from the new first checkpoint.
- Lost key: existing checkpoints can no longer be verified. Keep the old ledger, generate a new key (remove the
  `PAYASSIST_AUDIT_CHECKPOINT_KEY` line and run `scripts\add-missing-secrets.ps1`), rename the old ledger and start a
  new one. Key rotation is done the same way (one ledger per key).
- Restored database from a backup: checkpoints taken after the backup point refer to rows that no longer exist and
  will fail with `CHECKPOINTED_ROW_MISSING`. This is expected: archive the ledger together with the incident record
  and start a new ledger.

## Limitations
- Removing the **last lines of the ledger** is not detectable from the file alone: keep the latest `mac` (or the whole
  file) somewhere else, for example in the change ticket or a second machine.
- Changes made **between two checkpoints** that are then rewritten consistently before the next checkpoint are only
  bounded, not prevented: checkpoint often (at least daily, and after each deployment).
- The local ledger shares the host with the database; it protects against database-level tampering, not against a
  fully compromised host. That needs the production options above.
