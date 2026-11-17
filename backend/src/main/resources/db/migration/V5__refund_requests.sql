-- PayAssist AI - Phase 2, milestone 4: refund requests and supervisor approval.
-- Backward compatible: new tables, and one nullable audit column. No existing row is changed.
-- No money moves: an approved request records a REFUND_RECORDED fact in payment_events (simulated refund).

-- 1. Refund requests. A request in PENDING_APPROVAL or ESCALATION_REQUIRED *reserves* its amount: the
--    eligibility engine subtracts active reservations from what is still refundable. APPROVED consumes the
--    reservation (the amount is now a recorded refund); REJECTED releases it.
CREATE TABLE refund_requests (
    id                      UUID           PRIMARY KEY,
    refund_ref              VARCHAR(32)    NOT NULL UNIQUE,
    payment_id              UUID           NOT NULL REFERENCES payments (id),
    payment_ref             VARCHAR(32)    NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)     NOT NULL,
    reason_code             VARCHAR(64)    NOT NULL,
    status                  VARCHAR(32)    NOT NULL CHECK (status IN
                                ('PENDING_APPROVAL', 'ESCALATION_REQUIRED', 'APPROVED', 'REJECTED')),
    -- Eligibility as re-validated (under the payment row lock) when the request was created.
    eligibility_decision    VARCHAR(16)    NOT NULL CHECK (eligibility_decision IN ('ELIGIBLE', 'MANUAL_REVIEW')),
    eligibility_reasons     VARCHAR(500)   NOT NULL,
    policy_version          VARCHAR(64)    NOT NULL,
    requested_by_subject    VARCHAR(128)   NOT NULL,
    requested_by_username   VARCHAR(128)   NOT NULL,
    requested_at            TIMESTAMPTZ    NOT NULL,
    correlation_id          VARCHAR(64),
    -- Idempotency: one request per (requester, key). The fingerprint (SHA-256 of the canonical payload)
    -- detects reuse of a key with a different payload.
    idempotency_key         VARCHAR(64)    NOT NULL,
    request_fingerprint     VARCHAR(64)    NOT NULL,
    version                 BIGINT         NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_refund_requests_idempotency UNIQUE (requested_by_subject, idempotency_key)
);

CREATE INDEX idx_refund_requests_payment_status ON refund_requests (payment_id, status);
CREATE INDEX idx_refund_requests_status_requested ON refund_requests (status, requested_at DESC);

-- 2. Decisions: append-only, at most ONE per request (unique), so a request can never be approved or
--    rejected twice even if application checks were bypassed. An approval references the ledger event that
--    recorded the refund, also unique, so a refund is recorded exactly once.
CREATE TABLE refund_decisions (
    id                      UUID           PRIMARY KEY,
    refund_request_id       UUID           NOT NULL UNIQUE REFERENCES refund_requests (id),
    decision                VARCHAR(16)    NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason_code             VARCHAR(64)    NOT NULL,
    decided_by_subject      VARCHAR(128)   NOT NULL,
    decided_by_username     VARCHAR(128)   NOT NULL,
    decided_at              TIMESTAMPTZ    NOT NULL,
    correlation_id          VARCHAR(64),
    ledger_event_id         UUID           UNIQUE REFERENCES payment_events (id),
    CONSTRAINT ck_refund_decisions_ledger CHECK ((decision = 'APPROVED') = (ledger_event_id IS NOT NULL))
);

CREATE FUNCTION forbid_refund_decisions_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'refund_decisions is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_refund_decisions_no_update_delete
    BEFORE UPDATE OR DELETE ON refund_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_refund_decisions_modification();

CREATE TRIGGER trg_refund_decisions_no_truncate
    BEFORE TRUNCATE ON refund_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_refund_decisions_modification();

-- 3. Audit outcome: SUCCESS, or DENIED (authorization / separation of duties / limits) or FAILED (business
--    rule). Phase 1 rows keep NULL, which means SUCCESS (only successful reads were audited then).
ALTER TABLE audit_events ADD COLUMN outcome VARCHAR(16);
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_outcome
    CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'DENIED', 'FAILED'));
