-- PayAssist AI - Phase 2, milestone 2: richer, tamper-resistant audit trail.
-- Backward compatible: new columns are nullable (Phase 1 rows keep NULL); no row is changed.

-- Immutable user ID of the actor (OIDC 'sub'); 'actor' keeps the display username.
ALTER TABLE audit_events ADD COLUMN actor_subject VARCHAR(128);
-- Request correlation ID, to join an audit record with application logs.
ALTER TABLE audit_events ADD COLUMN correlation_id VARCHAR(64);

CREATE INDEX idx_audit_events_resource ON audit_events (resource_type, resource_id);

-- The audit trail is append-only, like payment history.
CREATE FUNCTION forbid_audit_events_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_events_modification();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_events_modification();
