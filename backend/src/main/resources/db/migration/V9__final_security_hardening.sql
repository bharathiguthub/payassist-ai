-- PayAssist AI - Phase 2, milestone 6.4: final security hardening (ADR 0013, sections 8-10).
--
-- Backward compatible and non-destructive: no row is changed. Adds privilege restrictions and guard triggers that the
-- application already satisfies (it changes only the columns listed below and only along its state machines).

-- 1. Case files: column-level UPDATE only -------------------------------------------------------------------------
-- V8 granted table-wide UPDATE; narrow it to the workflow columns the code changes. Everything else (amounts,
-- currency, payment, requester/opener, idempotency, eligibility snapshot, policy version) is immutable after INSERT.
REVOKE UPDATE ON refund_requests, dispute_cases FROM payassist_app;
GRANT UPDATE (status, updated_at, version) ON refund_requests TO payassist_app;
GRANT UPDATE (status, review_requested_by_subject, review_requested_by_username, review_requested_at, updated_at,
    version) ON dispute_cases TO payassist_app;

-- 2. Case files: rules enforced for every role, including the schema owner ---------------------------------------
-- The state machines duplicate RefundRequestTransitions / DisputeTransitions on purpose (defence in depth);
-- CaseTableGuardsIT fails the build if the SQL and Java tables ever differ.
CREATE FUNCTION refund_request_transition_allowed(from_status TEXT, to_status TEXT) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, public AS
$$
SELECT (from_status, to_status) IN (('PENDING_APPROVAL', 'APPROVED'), ('PENDING_APPROVAL', 'REJECTED'),
                                    ('ESCALATION_REQUIRED', 'REJECTED'))
$$;

CREATE FUNCTION dispute_case_transition_allowed(from_status TEXT, to_status TEXT) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, public AS
$$
SELECT (from_status, to_status) IN (
    ('OPEN', 'UNDER_INVESTIGATION'),
    ('UNDER_INVESTIGATION', 'PENDING_REVIEW'),
    ('PENDING_REVIEW', 'UNDER_INVESTIGATION'),
    ('PENDING_REVIEW', 'EVIDENCE_SUBMITTED'),
    ('EVIDENCE_SUBMITTED', 'WON'),
    ('EVIDENCE_SUBMITTED', 'LOST'),
    ('OPEN', 'LOST'), ('UNDER_INVESTIGATION', 'LOST'), ('PENDING_REVIEW', 'LOST'),
    ('OPEN', 'WITHDRAWN'), ('UNDER_INVESTIGATION', 'WITHDRAWN'), ('PENDING_REVIEW', 'WITHDRAWN'),
    ('EVIDENCE_SUBMITTED', 'WITHDRAWN'))
$$;

CREATE FUNCTION guard_refund_request_update() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public AS
$$
BEGIN
    IF (NEW.id, NEW.refund_ref, NEW.payment_id, NEW.payment_ref, NEW.amount, NEW.currency, NEW.reason_code,
        NEW.eligibility_decision, NEW.eligibility_reasons, NEW.policy_version, NEW.requested_by_subject,
        NEW.requested_by_username, NEW.requested_at, NEW.correlation_id, NEW.idempotency_key, NEW.request_fingerprint)
       IS DISTINCT FROM
       (OLD.id, OLD.refund_ref, OLD.payment_id, OLD.payment_ref, OLD.amount, OLD.currency, OLD.reason_code,
        OLD.eligibility_decision, OLD.eligibility_reasons, OLD.policy_version, OLD.requested_by_subject,
        OLD.requested_by_username, OLD.requested_at, OLD.correlation_id, OLD.idempotency_key, OLD.request_fingerprint)
    THEN
        RAISE EXCEPTION 'refund_requests: immutable columns cannot change (%)', OLD.refund_ref;
    END IF;
    IF OLD.status IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'refund_requests: % is decided (%) and final', OLD.refund_ref, OLD.status;
    END IF;
    IF NEW.status <> OLD.status AND NOT refund_request_transition_allowed(OLD.status, NEW.status) THEN
        RAISE EXCEPTION 'refund_requests: transition % -> % is not allowed', OLD.status, NEW.status;
    END IF;
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'refund_requests: version cannot go backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refund_requests_guard
    BEFORE UPDATE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION guard_refund_request_update();

CREATE FUNCTION guard_dispute_case_update() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public AS
$$
BEGIN
    IF (NEW.id, NEW.case_ref, NEW.payment_id, NEW.payment_ref, NEW.reason_category, NEW.network_reason_code,
        NEW.disputed_amount, NEW.currency, NEW.source, NEW.response_due_at, NEW.opened_by_subject,
        NEW.opened_by_username, NEW.opened_at, NEW.correlation_id, NEW.idempotency_key, NEW.request_fingerprint)
       IS DISTINCT FROM
       (OLD.id, OLD.case_ref, OLD.payment_id, OLD.payment_ref, OLD.reason_category, OLD.network_reason_code,
        OLD.disputed_amount, OLD.currency, OLD.source, OLD.response_due_at, OLD.opened_by_subject,
        OLD.opened_by_username, OLD.opened_at, OLD.correlation_id, OLD.idempotency_key, OLD.request_fingerprint)
    THEN
        RAISE EXCEPTION 'dispute_cases: immutable columns cannot change (%)', OLD.case_ref;
    END IF;
    IF OLD.status IN ('WON', 'LOST', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'dispute_cases: % is closed (%) and final', OLD.case_ref, OLD.status;
    END IF;
    IF NEW.status <> OLD.status AND NOT dispute_case_transition_allowed(OLD.status, NEW.status) THEN
        RAISE EXCEPTION 'dispute_cases: transition % -> % is not allowed', OLD.status, NEW.status;
    END IF;
    -- The review requester is recorded only when review is requested (it drives separation of duties).
    IF (NEW.review_requested_by_subject, NEW.review_requested_by_username, NEW.review_requested_at)
       IS DISTINCT FROM (OLD.review_requested_by_subject, OLD.review_requested_by_username, OLD.review_requested_at)
       AND NOT (OLD.status = 'UNDER_INVESTIGATION' AND NEW.status = 'PENDING_REVIEW') THEN
        RAISE EXCEPTION 'dispute_cases: the review requester can only be set when review is requested';
    END IF;
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'dispute_cases: version cannot go backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_dispute_cases_guard
    BEFORE UPDATE ON dispute_cases
    FOR EACH ROW EXECUTE FUNCTION guard_dispute_case_update();

-- Case files are never deleted either (the runtime role has no DELETE; this also stops the owner).
CREATE FUNCTION forbid_case_file_delete() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, public AS
$$
BEGIN
    RAISE EXCEPTION '%: case files cannot be deleted (%)', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_refund_requests_no_delete
    BEFORE DELETE ON refund_requests FOR EACH ROW EXECUTE FUNCTION forbid_case_file_delete();
CREATE TRIGGER trg_refund_requests_no_truncate
    BEFORE TRUNCATE ON refund_requests FOR EACH STATEMENT EXECUTE FUNCTION forbid_case_file_delete();
CREATE TRIGGER trg_dispute_cases_no_delete
    BEFORE DELETE ON dispute_cases FOR EACH ROW EXECUTE FUNCTION forbid_case_file_delete();
CREATE TRIGGER trg_dispute_cases_no_truncate
    BEFORE TRUNCATE ON dispute_cases FOR EACH STATEMENT EXECUTE FUNCTION forbid_case_file_delete();

-- 3. No temporary objects for application roles -----------------------------------------------------------------
-- TEMPORARY is granted to PUBLIC by default. Neither Hibernate nor the application uses temporary tables; revoking it
-- removes a place where a compromised session could stage data or shadow objects. The schema owner keeps it.
DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM payassist_app', current_database());
    EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO %I', current_database(), current_user);
END;
$$;

-- 4. Read-only auditor role for the external checkpoint job ------------------------------------------------------
-- Used only by AuditCheckpointTool (Compose "audit-checkpoint" tool). It can read the audit trail and nothing else;
-- the checkpoints themselves live outside PostgreSQL. Created NOLOGIN; DatabaseMigrator enables its login from
-- PAYASSIST_DB_AUDITOR_PASSWORD when that is configured.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payassist_auditor') THEN
        CREATE ROLE payassist_auditor NOLOGIN;
    END IF;
END;
$$;
ALTER ROLE payassist_auditor NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO payassist_auditor;
GRANT SELECT ON audit_events TO payassist_auditor;
DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM payassist_auditor', current_database());
END;
$$;
