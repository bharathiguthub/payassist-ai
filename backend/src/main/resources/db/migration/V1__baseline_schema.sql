-- PayAssist AI - Phase 1 baseline schema.
-- Note: no card numbers (PAN), CVV or bank account numbers are stored anywhere (PCI DSS scope reduction).
-- The pgvector extension is available in the database image but is intentionally NOT created here;
-- the RAG phase adds it in its own migration.

CREATE TABLE customers (
    id            UUID         PRIMARY KEY,
    customer_ref  VARCHAR(32)  NOT NULL UNIQUE,
    full_name     VARCHAR(200) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    country_code  VARCHAR(2)   NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE payments (
    id              UUID           PRIMARY KEY,
    payment_ref     VARCHAR(32)    NOT NULL UNIQUE,
    customer_id     UUID           NOT NULL REFERENCES customers (id),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    currency        VARCHAR(3)     NOT NULL,
    status          VARCHAR(32)    NOT NULL CHECK (status IN
                        ('PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED', 'DISPUTED')),
    payment_method  VARCHAR(32)    NOT NULL CHECK (payment_method IN ('CARD', 'BANK_TRANSFER', 'WALLET')),
    merchant_name   VARCHAR(200)   NOT NULL,
    failure_reason  VARCHAR(64),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_customer_id ON payments (customer_id);
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_payments_created_at ON payments (created_at DESC);

CREATE TABLE audit_events (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    actor          VARCHAR(128) NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64)  NOT NULL,
    resource_id    VARCHAR(128) NOT NULL
);

CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at DESC);
