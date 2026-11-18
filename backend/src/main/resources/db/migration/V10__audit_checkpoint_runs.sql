-- PayAssist AI - Phase 2, milestone 7: reported status of the external audit checkpoint job (ADR 0013 section 12).
--
-- Backward compatible: one new table, no existing row is changed.
-- Every run of AuditCheckpointTool (verify or create) appends one row, so the admin UI can show when the external
-- checkpoints were last verified and with what result. This is a REPORTED status: the proof stays in the
-- HMAC-signed ledger outside PostgreSQL. The table holds no key, MAC, file path or full hash.
--   * payassist_auditor (the checkpoint job) may only INSERT and SELECT;
--   * payassist_app (the backend) may only SELECT, so it cannot fake a status;
--   * rows are append-only for every role (trigger), like the audit trail.

CREATE TABLE audit_checkpoint_runs (
    id                       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ran_at                   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    mode                     VARCHAR(8)   NOT NULL CHECK (mode IN ('VERIFY', 'CREATE')),
    result                   VARCHAR(8)   NOT NULL CHECK (result IN ('OK', 'FAILED')),
    -- Stable reason code when FAILED, e.g. CHECKPOINT_MISMATCH, LEDGER_MAC_MISMATCH, DB_CHAIN_ROW_HASH_MISMATCH.
    reason                   VARCHAR(64)  CHECK (reason IS NULL OR reason ~ '^[A-Z][A-Z_]{0,63}$'),
    checkpoints              INTEGER      NOT NULL CHECK (checkpoints >= 0),
    appended                 BOOLEAN      NOT NULL,
    -- The newest checkpoint in the ledger after this run (NULL when the ledger is empty).
    latest_checkpoint_head   BIGINT       CHECK (latest_checkpoint_head IS NULL OR latest_checkpoint_head > 0),
    latest_checkpoint_at     TIMESTAMPTZ,
    -- Head of the database chain seen by this run, and a short prefix of its hash for display only.
    db_head_chain_seq        BIGINT       CHECK (db_head_chain_seq IS NULL OR db_head_chain_seq > 0),
    db_head_hash_prefix      VARCHAR(16)  CHECK (db_head_hash_prefix IS NULL OR db_head_hash_prefix ~ '^[0-9a-f]{16}$'),
    CHECK ((result = 'OK') = (reason IS NULL))
);

CREATE INDEX idx_audit_checkpoint_runs_ran_at ON audit_checkpoint_runs (ran_at DESC);

CREATE FUNCTION forbid_checkpoint_run_modification() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public AS
$$
BEGIN
    RAISE EXCEPTION 'audit_checkpoint_runs is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_checkpoint_runs_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_checkpoint_runs
    FOR EACH ROW EXECUTE FUNCTION forbid_checkpoint_run_modification();
CREATE TRIGGER trg_audit_checkpoint_runs_no_truncate
    BEFORE TRUNCATE ON audit_checkpoint_runs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_checkpoint_run_modification();

GRANT SELECT ON audit_checkpoint_runs TO payassist_app;
GRANT SELECT, INSERT ON audit_checkpoint_runs TO payassist_auditor;
GRANT USAGE ON SEQUENCE audit_checkpoint_runs_id_seq TO payassist_auditor;
