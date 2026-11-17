-- SYNTHETIC DATA ONLY - deterministic lifecycle histories for the synthetic seed payments.
-- Loaded only by the 'dev' profile (and tests). Flyway runs repeatable migrations in description order,
-- so this ("synthetic seed lifecycle") runs after "synthetic seed data", which creates the payments.
--
-- Each payment gets the chain of events that leads to its current status. Timings are compressed into
-- the two hours between the seed's created_at and updated_at (the seed sets updated_at = created_at + 2h).
--
-- Idempotent and non-destructive:
--   * only payments without any non-migration event are seeded, so re-runs add nothing;
--   * on a database upgraded from Phase 1, V2 already added a LEGACY_SNAPSHOT event (sequence 1). The
--     history is appended after it (higher sequence numbers, earlier occurred_at), nothing is replaced;
--   * deterministic IDs + ON CONFLICT DO NOTHING as a second guard.

INSERT INTO payment_events (id, payment_id, sequence_no, event_type, from_status, to_status, amount, currency,
                            reason_code, actor_subject, actor_username, correlation_id, source, occurred_at)
SELECT md5('payment-event-' || s.payment_ref || '-' || chain.ord)::uuid,
       s.id,
       s.seq_offset + chain.ord,
       chain.event_type,
       chain.from_status,
       chain.to_status,
       s.amount,
       s.currency,
       CASE chain.event_type
           WHEN 'FAILED' THEN s.failure_reason
           WHEN 'DISPUTE_OPENED' THEN (ARRAY['PRODUCT_NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'FRAUDULENT',
                                             'DUPLICATE_CHARGE'])[1 + (s.n % 4)]
           WHEN 'REFUND_RECORDED' THEN 'CUSTOMER_REQUEST'
       END,
       NULL,
       CASE WHEN chain.source = 'CARD_NETWORK' THEN 'system:card-network-simulator'
            ELSE 'system:processor-simulator' END,
       'seed-' || lower(s.payment_ref),
       chain.source,
       s.created_at + chain.after_created
FROM (
    SELECT p.id, p.payment_ref, p.amount, p.currency, p.status, p.failure_reason, p.created_at,
           substring(p.payment_ref FROM 5)::int AS n,
           COALESCE((SELECT max(e.sequence_no) FROM payment_events e WHERE e.payment_id = p.id), 0) AS seq_offset
    FROM payments p
    WHERE p.payment_ref ~ '^PAY-[0-9]{6}$'
      AND NOT EXISTS (SELECT 1 FROM payment_events e WHERE e.payment_id = p.id AND e.source <> 'MIGRATION')
) AS s
JOIN (VALUES
    ('PENDING',    1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),

    ('AUTHORIZED', 1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('AUTHORIZED', 2, 'AUTHORIZED',      'PENDING',    'AUTHORIZED', 'PROCESSOR',    interval '3 seconds'),

    ('CAPTURED',   1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('CAPTURED',   2, 'AUTHORIZED',      'PENDING',    'AUTHORIZED', 'PROCESSOR',    interval '3 seconds'),
    ('CAPTURED',   3, 'CAPTURED',        'AUTHORIZED', 'CAPTURED',   'PROCESSOR',    interval '2 hours'),

    ('SETTLED',    1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('SETTLED',    2, 'AUTHORIZED',      'PENDING',    'AUTHORIZED', 'PROCESSOR',    interval '3 seconds'),
    ('SETTLED',    3, 'CAPTURED',        'AUTHORIZED', 'CAPTURED',   'PROCESSOR',    interval '10 minutes'),
    ('SETTLED',    4, 'SETTLED',         'CAPTURED',   'SETTLED',    'PROCESSOR',    interval '2 hours'),

    ('FAILED',     1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('FAILED',     2, 'FAILED',          'PENDING',    'FAILED',     'PROCESSOR',    interval '4 seconds'),

    ('REFUNDED',   1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('REFUNDED',   2, 'AUTHORIZED',      'PENDING',    'AUTHORIZED', 'PROCESSOR',    interval '3 seconds'),
    ('REFUNDED',   3, 'CAPTURED',        'AUTHORIZED', 'CAPTURED',   'PROCESSOR',    interval '10 minutes'),
    ('REFUNDED',   4, 'SETTLED',         'CAPTURED',   'SETTLED',    'PROCESSOR',    interval '1 hour'),
    ('REFUNDED',   5, 'REFUND_RECORDED', 'SETTLED',    'REFUNDED',   'PROCESSOR',    interval '2 hours'),

    ('DISPUTED',   1, 'CREATED',         NULL,         'PENDING',    'PROCESSOR',    interval '0'),
    ('DISPUTED',   2, 'AUTHORIZED',      'PENDING',    'AUTHORIZED', 'PROCESSOR',    interval '3 seconds'),
    ('DISPUTED',   3, 'CAPTURED',        'AUTHORIZED', 'CAPTURED',   'PROCESSOR',    interval '10 minutes'),
    ('DISPUTED',   4, 'SETTLED',         'CAPTURED',   'SETTLED',    'PROCESSOR',    interval '1 hour'),
    ('DISPUTED',   5, 'DISPUTE_OPENED',  'SETTLED',    'DISPUTED',   'CARD_NETWORK', interval '2 hours')
) AS chain (final_status, ord, event_type, from_status, to_status, source, after_created)
    ON chain.final_status = s.status
ON CONFLICT (id) DO NOTHING;
