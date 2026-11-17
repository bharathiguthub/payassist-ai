-- PayAssist AI - Phase 2, milestone 5: dispute case management.
-- Backward compatible: one widened CHECK and new tables. No existing row is changed.
--
-- A dispute case is PayAssist's investigation file for a cardholder dispute raised through the customer's bank,
-- card network or wallet provider. The case lifecycle is separate from money: opening a case puts the payment in
-- DISPUTED (an investigation status: nothing is reversed or charged back), and only a recorded LOST outcome moves the
-- payment to CHARGED_BACK. No money moves in PayAssist.

-- 1. Payment ledger: the cardholder can withdraw a dispute (restores the pre-dispute status).
ALTER TABLE payment_events DROP CONSTRAINT payment_events_event_type_check;
ALTER TABLE payment_events ADD CONSTRAINT payment_events_event_type_check CHECK (event_type IN
    ('LEGACY_SNAPSHOT', 'CREATED', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'VOIDED',
     'REFUND_RECORDED', 'DISPUTE_OPENED', 'DISPUTE_WON', 'DISPUTE_LOST', 'DISPUTE_WITHDRAWN',
     'REFUND_REQUESTED', 'REFUND_REJECTED', 'REFUND_CANCELLED', 'REFUND_ESCALATED', 'DISPUTE_UPDATED'));

-- 2. Dispute cases.
CREATE TABLE dispute_cases (
    id                            UUID           PRIMARY KEY,
    case_ref                      VARCHAR(32)    NOT NULL UNIQUE,
    payment_id                    UUID           NOT NULL REFERENCES payments (id),
    payment_ref                   VARCHAR(32)    NOT NULL,
    reason_category               VARCHAR(32)    NOT NULL CHECK (reason_category IN
                                      ('FRAUDULENT', 'PRODUCT_NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'DUPLICATE_CHARGE',
                                       'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED', 'OTHER')),
    -- The network's or provider's own reason code, stored exactly as provided and never interpreted.
    network_reason_code           VARCHAR(16),
    -- Phase 2: always the full unrefunded amount at opening (partial disputes are out of scope).
    disputed_amount               NUMERIC(19, 2) NOT NULL CHECK (disputed_amount > 0),
    currency                      VARCHAR(3)     NOT NULL,
    status                        VARCHAR(32)    NOT NULL CHECK (status IN
                                      ('OPEN', 'UNDER_INVESTIGATION', 'PENDING_REVIEW', 'EVIDENCE_SUBMITTED',
                                       'WON', 'LOST', 'WITHDRAWN')),
    source                        VARCHAR(16)    NOT NULL CHECK (source IN ('PAYASSIST', 'CARD_NETWORK')),
    -- Response deadline as provided by the user from the bank/network notification. Never computed by PayAssist.
    response_due_at               TIMESTAMPTZ,
    opened_by_subject             VARCHAR(128),
    opened_by_username            VARCHAR(128)   NOT NULL,
    opened_at                     TIMESTAMPTZ    NOT NULL,
    review_requested_by_subject   VARCHAR(128),
    review_requested_by_username  VARCHAR(128),
    review_requested_at           TIMESTAMPTZ,
    correlation_id                VARCHAR(64),
    idempotency_key               VARCHAR(64),
    request_fingerprint           VARCHAR(64),
    version                       BIGINT         NOT NULL DEFAULT 0,
    updated_at                    TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_dispute_cases_idempotency UNIQUE (opened_by_subject, idempotency_key),
    -- Cases opened in PayAssist always have a human opener and an idempotency key; card-network (system) cases do not.
    CONSTRAINT ck_dispute_cases_opener CHECK (source = 'CARD_NETWORK'
        OR (opened_by_subject IS NOT NULL AND idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL))
);

-- At most one active (non-final) case per payment, even under concurrent creation.
CREATE UNIQUE INDEX uq_dispute_cases_one_active_per_payment ON dispute_cases (payment_id)
    WHERE status IN ('OPEN', 'UNDER_INVESTIGATION', 'PENDING_REVIEW', 'EVIDENCE_SUBMITTED');
CREATE INDEX idx_dispute_cases_status_due ON dispute_cases (status, response_due_at);
CREATE INDEX idx_dispute_cases_opened_at ON dispute_cases (opened_at DESC);

-- 3. Case history: every transition, note and evidence addition, append-only.
CREATE TABLE dispute_case_events (
    id               UUID          PRIMARY KEY,
    case_id          UUID          NOT NULL REFERENCES dispute_cases (id),
    sequence_no      INTEGER       NOT NULL CHECK (sequence_no > 0),
    event_type       VARCHAR(40)   NOT NULL CHECK (event_type IN
                         ('OPENED', 'INVESTIGATION_STARTED', 'REVIEW_REQUESTED', 'RETURNED_FOR_INVESTIGATION',
                          'SUBMISSION_APPROVED', 'OUTCOME_WON', 'OUTCOME_LOST', 'LIABILITY_ACCEPTED', 'WITHDRAWN',
                          'NOTE_ADDED', 'EVIDENCE_ADDED')),
    from_status      VARCHAR(32),
    to_status        VARCHAR(32),
    reason_code      VARCHAR(64),
    actor_subject    VARCHAR(128),
    actor_username   VARCHAR(128)  NOT NULL,
    correlation_id   VARCHAR(64),
    occurred_at      TIMESTAMPTZ   NOT NULL,
    -- The payment ledger event this case event caused (opening and final outcomes), recorded exactly once.
    ledger_event_id  UUID          UNIQUE REFERENCES payment_events (id),
    CONSTRAINT uq_dispute_case_events_sequence UNIQUE (case_id, sequence_no)
);

-- 4. Investigation notes: free text (validated), append-only; corrections are new notes.
CREATE TABLE dispute_notes (
    id                   UUID           PRIMARY KEY,
    case_id              UUID           NOT NULL REFERENCES dispute_cases (id),
    body                 VARCHAR(2000)  NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    author_subject       VARCHAR(128)   NOT NULL,
    author_username      VARCHAR(128)   NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL,
    correlation_id       VARCHAR(64),
    idempotency_key      VARCHAR(64)    NOT NULL,
    request_fingerprint  VARCHAR(64)    NOT NULL,
    CONSTRAINT uq_dispute_notes_idempotency UNIQUE (author_subject, idempotency_key)
);
CREATE INDEX idx_dispute_notes_case ON dispute_notes (case_id, created_at);

-- 5. Evidence metadata only (no files are stored): what it is and where the document lives.
CREATE TABLE dispute_evidence (
    id                   UUID           PRIMARY KEY,
    case_id              UUID           NOT NULL REFERENCES dispute_cases (id),
    evidence_type        VARCHAR(32)    NOT NULL CHECK (evidence_type IN
                             ('PROOF_OF_DELIVERY', 'CUSTOMER_COMMUNICATION', 'TRANSACTION_RECEIPT', 'TERMS_AND_POLICIES',
                              'SERVICE_USAGE_RECORD', 'REFUND_OR_CREDIT_RECORD', 'OTHER')),
    document_reference   VARCHAR(128)   NOT NULL,
    description          VARCHAR(500),
    received_at          TIMESTAMPTZ,
    added_by_subject     VARCHAR(128)   NOT NULL,
    added_by_username    VARCHAR(128)   NOT NULL,
    added_at             TIMESTAMPTZ    NOT NULL,
    correlation_id       VARCHAR(64),
    idempotency_key      VARCHAR(64)    NOT NULL,
    request_fingerprint  VARCHAR(64)    NOT NULL,
    CONSTRAINT uq_dispute_evidence_idempotency UNIQUE (added_by_subject, idempotency_key)
);
CREATE INDEX idx_dispute_evidence_case ON dispute_evidence (case_id, added_at);

-- 6. History, notes and evidence are immutable.
CREATE FUNCTION forbid_append_only_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_dispute_case_events_append_only BEFORE UPDATE OR DELETE ON dispute_case_events
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_modification();
CREATE TRIGGER trg_dispute_case_events_no_truncate BEFORE TRUNCATE ON dispute_case_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_append_only_modification();
CREATE TRIGGER trg_dispute_notes_append_only BEFORE UPDATE OR DELETE ON dispute_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_modification();
CREATE TRIGGER trg_dispute_notes_no_truncate BEFORE TRUNCATE ON dispute_notes
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_append_only_modification();
CREATE TRIGGER trg_dispute_evidence_append_only BEFORE UPDATE OR DELETE ON dispute_evidence
    FOR EACH ROW EXECUTE FUNCTION forbid_append_only_modification();
CREATE TRIGGER trg_dispute_evidence_no_truncate BEFORE TRUNCATE ON dispute_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_append_only_modification();
