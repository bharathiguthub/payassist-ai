# Runbook — restricted runtime database role (`payassist_app`)

Context: [ADR 0013](../adr/0013-tamper-evident-audit-and-least-privilege.md) §7, migration
`V8__runtime_role.sql`, job `com.payassist.DatabaseMigrator`. All commands from the repository root (PowerShell).

| Credential | Used by | Can do |
|---|---|---|
| `POSTGRES_USER` (schema owner) | `migrate` job only (Flyway), manual admin work | everything (owner; superuser in the local container) |
| `payassist_app` / `PAYASSIST_DB_APP_PASSWORD` | `backend` | the DML in V8 only; no DDL, no DELETE/TRUNCATE, owns nothing, no grant options |

## Apply (existing environment, done once)
```powershell
# 0. Backup (git-ignored backups/ folder) and verify it restores
docker exec payassist-postgres-1 sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/backup.dump'
docker cp payassist-postgres-1:/tmp/backup.dump backups\payassist-<timestamp>.dump
# 1. Add the runtime password to .env (random, 256-bit, never printed)
.\scripts\add-app-db-password.ps1
# 2. Build the image and run the one-off migrate job (V8 + login for payassist_app)
docker compose build backend
docker compose up --no-deps --abort-on-container-exit --exit-code-from migrate migrate
# 3. Recreate only the application containers (Postgres and Keycloak are not touched)
docker compose up -d --no-deps --wait backend frontend
```
A fresh environment needs nothing special: `new-dev-env.ps1` generates the password and `docker compose up` runs the
migrate job before the backend.

## Verify
```powershell
docker exec payassist-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select usename, application_name from pg_stat_activity where datname = current_database()"'
.\scripts\smoke-test.ps1        # includes the audit chain integrity check
```
The backend's sessions show `usename = payassist_app`.

## Rotate the runtime password
Edit `PAYASSIST_DB_APP_PASSWORD` in `.env` (or delete the line and run `add-app-db-password.ps1`), then repeat
steps 2 and 3. The migrate job re-applies the password on every run.

## Roll back (application runs as the owner again)
Data is unaffected in both directions; V8 only adds a role, grants and function settings. Keep V8 and the Java
changes (Flyway would reject a code base that no longer contains an applied migration); restore only the
deployment files from the last commit before M6.3 (`71e049e`) and recreate the application containers:
```powershell
git checkout 71e049e -- docker-compose.yml scripts/dev-env.ps1
docker compose up -d --build --no-deps --wait backend frontend   # backend: owner credentials, Flyway enabled again
docker compose rm -f migrate                                     # optional: remove the exited job container
```
Postgres and Keycloak are not touched. To undo the rollback: `git checkout HEAD -- docker-compose.yml
scripts/dev-env.ps1` and repeat steps 2–3 of *Apply*. Optionally remove the role afterwards, as the owner:
```sql
DROP OWNED BY payassist_app;   -- revokes its grants (it owns nothing)
DROP ROLE payassist_app;
```
(Only after the backend no longer uses it; V8 would recreate it NOLOGIN on a fresh database.)

## Restore from the backup (last resort)
Only if data was damaged. Stop the app first; restore into a new database, check it, then switch over.
```powershell
docker compose stop backend frontend
docker cp backups\<file>.dump payassist-postgres-1:/tmp/restore.dump
docker exec payassist-postgres-1 sh -c 'createdb -U "$POSTGRES_USER" payassist_restored && pg_restore -U "$POSTGRES_USER" -d payassist_restored --exit-on-error /tmp/restore.dump'
```
Then point `POSTGRES_DB` at `payassist_restored` (or rename databases while the app is stopped) and start the stack.
The audit chain of a restored database verifies as before; anything written after the backup is not in it.
