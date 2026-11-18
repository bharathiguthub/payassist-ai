-- PayAssist AI - Phase 2, milestone 6: tamper-evident audit trail (ADR 0013).
--
-- Backward compatible and non-destructive: new nullable columns, new functions and one new trigger. No existing
-- audit row is changed. Rows written before this migration stay "legacy" (chain_seq IS NULL); a genesis anchor
-- row records how many legacy rows there were, the highest legacy ID and a SHA-256 digest over their content,
-- so later deletion or modification of legacy rows is detectable too.
--
-- Every row inserted from now on is chained: a BEFORE INSERT trigger assigns chain_seq = previous + 1,
-- prev_hash = previous row_hash and row_hash = SHA-256(canonical row). The trigger always overwrites these
-- columns, so a writer cannot choose them. AuditIntegrityVerifier (Java) recomputes the chain independently.
--
-- Canonical encoding (version 1). Every field is length-prefixed so no separator can be forged:
--   field  := octet_length(value) ':' value ';'      (NULL is '-;')
--   details:= one field holding, for each key in byte order, field(key) field(value)
--   chained row  := 'v1;' id chain_seq prev_hash occurred_at actor actor_subject actor_type on_behalf_of_subject
--                   action resource_type resource_id correlation_id outcome details
--   legacy row   := 'L1;' id occurred_at actor actor_subject action resource_type resource_id correlation_id
--                   outcome details
--   occurred_at  := UTC, microseconds, e.g. 2026-11-16T12:00:00.000000Z

-- 1. New columns -------------------------------------------------------------------------------------------------
-- Who acted: a human USER, a SYSTEM process, or (later phases) an AI AGENT acting on behalf of a user.
ALTER TABLE audit_events ADD COLUMN actor_type VARCHAR(16);
-- Reserved for agent delegation: the human subject an AGENT acted for. Always NULL in this phase.
ALTER TABLE audit_events ADD COLUMN on_behalf_of_subject VARCHAR(128);
ALTER TABLE audit_events ADD COLUMN chain_seq BIGINT;
ALTER TABLE audit_events ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN row_hash VARCHAR(64);

ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_actor_type
    CHECK (actor_type IS NULL OR actor_type IN ('USER', 'SYSTEM', 'AGENT'));
ALTER TABLE audit_events ADD CONSTRAINT uq_audit_events_chain_seq UNIQUE (chain_seq);
-- Either a legacy row (no chain columns) or a fully chained row.
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_chain_columns CHECK (
    (chain_seq IS NULL AND prev_hash IS NULL AND row_hash IS NULL)
    OR (chain_seq > 0 AND actor_type IS NOT NULL
        AND prev_hash ~ '^[0-9a-f]{64}$' AND row_hash ~ '^[0-9a-f]{64}$'));

CREATE INDEX idx_audit_events_actor_subject ON audit_events (actor_subject);
CREATE INDEX idx_audit_events_action ON audit_events (action);
CREATE INDEX idx_audit_events_correlation_id ON audit_events (correlation_id);

-- 2. Canonical encoding ------------------------------------------------------------------------------------------
CREATE FUNCTION audit_field(v TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$$
SELECT CASE WHEN v IS NULL THEN '-;' ELSE octet_length(v) || ':' || v || ';' END
$$;

CREATE FUNCTION audit_details_canonical(d JSONB) RETURNS TEXT
    LANGUAGE sql STABLE AS
$$
SELECT CASE WHEN d IS NULL THEN NULL ELSE coalesce(
    (SELECT string_agg(audit_field(e.key) || audit_field(e.value), '' ORDER BY e.key COLLATE "C")
     FROM jsonb_each_text(d) AS e), '') END
$$;

CREATE FUNCTION audit_timestamp(t TIMESTAMPTZ) RETURNS TEXT
    LANGUAGE sql STABLE AS
$$
SELECT to_char(t AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

CREATE FUNCTION audit_row_canonical(r audit_events) RETURNS TEXT
    LANGUAGE sql STABLE AS
$$
SELECT 'v1;' || audit_field(r.id::text) || audit_field(r.chain_seq::text) || audit_field(r.prev_hash)
    || audit_field(audit_timestamp(r.occurred_at)) || audit_field(r.actor) || audit_field(r.actor_subject)
    || audit_field(r.actor_type) || audit_field(r.on_behalf_of_subject) || audit_field(r.action)
    || audit_field(r.resource_type) || audit_field(r.resource_id) || audit_field(r.correlation_id)
    || audit_field(r.outcome) || audit_field(audit_details_canonical(r.details))
$$;

CREATE FUNCTION audit_legacy_canonical(r audit_events) RETURNS TEXT
    LANGUAGE sql STABLE AS
$$
SELECT 'L1;' || audit_field(r.id::text) || audit_field(audit_timestamp(r.occurred_at)) || audit_field(r.actor)
    || audit_field(r.actor_subject) || audit_field(r.action) || audit_field(r.resource_type)
    || audit_field(r.resource_id) || audit_field(r.correlation_id) || audit_field(r.outcome)
    || audit_field(audit_details_canonical(r.details))
$$;

-- 3. Chaining trigger --------------------------------------------------------------------------------------------
-- A transaction-scoped advisory lock serializes audit inserts, so each row links to the committed head. The
-- lock is held until the inserting transaction ends. Snapshot isolation (REPEATABLE READ / SERIALIZABLE) would
-- hide a head committed after the snapshot and fork the chain, so such writes are refused (fail closed); the
-- UNIQUE chain_seq constraint is the last line of defence against forks.
CREATE FUNCTION audit_events_chain() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    head_seq  BIGINT;
    head_hash VARCHAR(64);
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'audit_events must be written in a READ COMMITTED transaction (was %)',
            current_setting('transaction_isolation');
    END IF;
    PERFORM pg_advisory_xact_lock(730200601);  -- PayAssist audit chain lock

    SELECT chain_seq, row_hash INTO head_seq, head_hash
    FROM audit_events WHERE chain_seq IS NOT NULL ORDER BY chain_seq DESC LIMIT 1;

    IF head_seq IS NULL THEN
        NEW.chain_seq := 1;
        NEW.prev_hash := repeat('0', 64);
    ELSE
        NEW.chain_seq := head_seq + 1;
        NEW.prev_hash := head_hash;
    END IF;
    IF NEW.actor_type IS NULL THEN
        NEW.actor_type := CASE WHEN NEW.actor_subject IS NULL AND NEW.actor LIKE 'system:%' THEN 'SYSTEM'
                               ELSE 'USER' END;
    END IF;
    NEW.row_hash := encode(sha256(convert_to(audit_row_canonical(NEW), 'UTF8')), 'hex');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_events_chain
    BEFORE INSERT ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_chain();

-- 4. Genesis anchor ----------------------------------------------------------------------------------------------
-- The first chained row. Its details pin the legacy rows: count, highest ID and a digest over their canonical
-- form in ID order (the digest of an empty string when there are none).
INSERT INTO audit_events (occurred_at, actor, action, resource_type, resource_id, actor_type, outcome, details)
SELECT now(), 'system:flyway', 'AUDIT_CHAIN_GENESIS', 'AUDIT_CHAIN', 'genesis', 'SYSTEM', 'SUCCESS',
       jsonb_build_object(
           'canonicalVersion', '1',
           'legacyRows', count(*)::text,
           'legacyMaxId', coalesce(max(a.id), 0)::text,
           'legacyDigest', encode(sha256(convert_to(
               coalesce(string_agg(audit_legacy_canonical(a), '' ORDER BY a.id), ''), 'UTF8')), 'hex'))
FROM audit_events a;
