-- PayAssist AI - Phase 2, milestone 1: payment lifecycle and append-only event history.
--
-- Backward compatible with Phase 1:
--   * the payments status CHECK is only WIDENED (every existing value stays valid);
--   * payments.version is added with a default, so existing rows and Phase 1 code keep working;
--   * payment_events is a new table; existing payments get one LEGACY_SNAPSHOT event so every payment
--     has a history. No existing row is updated or deleted.

-- 1. New lifecycle statuses: VOIDED (authorization released), PARTIALLY_REFUNDED, CHARGED_BACK (dispute lost).
--    The constraint name is PostgreSQL's default for the V1 column CHECK. No IF EXISTS on purpose:
--    if the name were different this migration must fail rather than silently keep the old rule.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN
    ('PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'VOIDED',
     'PARTIALLY_REFUNDED', 'REFUNDED', 'DISPUTED', 'CHARGED_BACK'));

-- 2. Optimistic-locking version. PostgreSQL 11+ adds a column with a constant default without a table rewrite.
ALTER TABLE payments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 3. Append-only history of everything that happened to a payment.
CREATE TABLE payment_events (
    id              UUID           PRIMARY KEY,
    payment_id      UUID           NOT NULL REFERENCES payments (id),
    -- Order in which events were RECORDED for this payment (gap-free, assigned under a row lock).
    -- occurred_at may be earlier than a previous event's (late-arriving processor messages).
    sequence_no     INTEGER        NOT NULL CHECK (sequence_no > 0),
    event_type      VARCHAR(40)    NOT NULL CHECK (event_type IN
                        ('LEGACY_SNAPSHOT', 'CREATED', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'VOIDED',
                         'REFUND_RECORDED', 'DISPUTE_OPENED', 'DISPUTE_WON', 'DISPUTE_LOST',
                         'REFUND_REQUESTED', 'REFUND_REJECTED', 'REFUND_CANCELLED', 'REFUND_ESCALATED',
                         'DISPUTE_UPDATED')),
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    amount          NUMERIC(19, 2) CHECK (amount >= 0),
    currency        VARCHAR(3),
    reason_code     VARCHAR(64),
    -- Who caused the event: the Keycloak subject (immutable user ID; NULL for system actors) and the
    -- username at the time (for display). System actors use 'system:<name>'.
    actor_subject   VARCHAR(128),
    actor_username  VARCHAR(128)   NOT NULL,
    correlation_id  VARCHAR(64),
    source          VARCHAR(32)    NOT NULL CHECK (source IN ('PROCESSOR', 'CARD_NETWORK', 'PAYASSIST', 'MIGRATION')),
    occurred_at     TIMESTAMPTZ    NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_events_sequence UNIQUE (payment_id, sequence_no),
    CONSTRAINT ck_payment_events_amount_currency CHECK ((amount IS NULL) = (currency IS NULL))
);

CREATE INDEX idx_payment_events_payment_occurred ON payment_events (payment_id, occurred_at, sequence_no);

-- 4. History is immutable: corrections are new events, never edits. Enforced in the database so that
--    no code path (or ad-hoc SQL session) can rewrite or erase it.
CREATE FUNCTION payment_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'payment_events is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_payment_events_no_update_delete
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION payment_events_append_only();

CREATE TRIGGER trg_payment_events_no_truncate
    BEFORE TRUNCATE ON payment_events
    FOR EACH STATEMENT EXECUTE FUNCTION payment_events_append_only();

-- 5. Backfill: one snapshot event per pre-existing payment, recording its status as of this migration.
--    Deterministic IDs keep this idempotent. On a fresh database payments is empty here (seed data is a
--    repeatable migration that runs after all versioned ones), so nothing is inserted.
INSERT INTO payment_events (id, payment_id, sequence_no, event_type, from_status, to_status, amount, currency,
                            reason_code, actor_subject, actor_username, correlation_id, source, occurred_at)
SELECT md5('legacy-snapshot-' || p.id::text)::uuid,
       p.id,
       1,
       'LEGACY_SNAPSHOT',
       NULL,
       p.status,
       p.amount,
       p.currency,
       p.failure_reason,
       NULL,
       'system:migration',
       NULL,
       'MIGRATION',
       p.updated_at
FROM payments p
WHERE NOT EXISTS (SELECT 1 FROM payment_events e WHERE e.payment_id = p.id);
