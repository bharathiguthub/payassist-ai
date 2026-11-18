-- PayAssist AI - Phase 2, milestone 6.3: least-privilege runtime database role (ADR 0013, decision 2).
--
-- Flyway keeps running as the schema owner. The application connects as payassist_app, which:
--   * owns nothing (cannot ALTER/DROP tables, disable triggers or replace functions),
--   * has no role attributes (no SUPERUSER, CREATEROLE, CREATEDB, REPLICATION, BYPASSRLS),
--   * gets only the DML the code needs, never DELETE, TRUNCATE, TRIGGER or REFERENCES,
--   * gets nothing WITH GRANT OPTION, so it cannot pass on or widen its own privileges.
-- No row is changed. The role is created without LOGIN; login and password are set outside migrations
-- (DatabaseMigrator from PAYASSIST_DB_APP_PASSWORD, or the test container's init script), so no secret is ever part
-- of a migration or its Flyway history.
--
-- Every later migration that creates a table must grant payassist_app exactly what it needs (the integration tests
-- run as this role and fail otherwise).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payassist_app') THEN
        CREATE ROLE payassist_app NOLOGIN;
    END IF;
END;
$$;

-- Enforce the attributes even if the role was created beforehand (e.g. by a DBA or a test init script).
ALTER ROLE payassist_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- Schema: use, never create. (PostgreSQL 15+ already revokes CREATE from PUBLIC; made explicit.)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO payassist_app;

-- Reference data: read only.
GRANT SELECT ON customers TO payassist_app;

-- Payments: amounts, currency, method, merchant and references are immutable facts; the ledger only moves status.
GRANT SELECT ON payments TO payassist_app;
GRANT UPDATE (status, failure_reason, updated_at, version) ON payments TO payassist_app;

-- Append-only history: read and add, never change (UPDATE/DELETE/TRUNCATE are also blocked by triggers).
GRANT SELECT, INSERT ON payment_events, audit_events, refund_decisions,
    dispute_case_events, dispute_notes, dispute_evidence TO payassist_app;

-- Case files with workflow state.
GRANT SELECT, INSERT, UPDATE ON refund_requests, dispute_cases TO payassist_app;

-- Identity sequences (audit_events.id).
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO payassist_app;

-- Pin the search_path of every function owned by the schema owner (trigger functions, audit canonical form), so a
-- session-level search_path can never redirect them to look-alike objects.
DO $$
DECLARE
    f RECORD;
BEGIN
    FOR f IN
        SELECT p.oid::regprocedure AS signature
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.prokind = 'f' AND p.proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
    LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path = pg_catalog, public', f.signature);
    END LOOP;
END;
$$;
