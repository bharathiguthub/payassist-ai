-- SYNTHETIC DATA ONLY - dispute case files for the synthetic seed payments that are already DISPUTED.
-- Loaded only by the 'dev' profile (and tests). Runs after "synthetic seed lifecycle" (description order), which
-- recorded those payments' DISPUTE_OPENED events.
--
-- Non-destructive and idempotent:
--   * no payment row and no payment event is changed or added; cases only reference the existing DISPUTE_OPENED event;
--   * only payments that are DISPUTED, carry a seed DISPUTE_OPENED event and have no dispute case at all;
--   * deterministic IDs + ON CONFLICT DO NOTHING.
-- The cases are CARD_NETWORK cases (system opener), OPEN, with no response deadline: PayAssist never invents one.

INSERT INTO dispute_cases (id, case_ref, payment_id, payment_ref, reason_category, network_reason_code, disputed_amount,
                           currency, status, source, response_due_at, opened_by_subject, opened_by_username, opened_at,
                           correlation_id, idempotency_key, request_fingerprint, version, updated_at)
SELECT md5('dispute-case-' || p.payment_ref)::uuid,
       'DSP-' || upper(substr(md5('dispute-case-' || p.payment_ref), 1, 12)),
       p.id,
       p.payment_ref,
       CASE WHEN e.reason_code IN ('FRAUDULENT', 'PRODUCT_NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'DUPLICATE_CHARGE',
                                   'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED')
            THEN e.reason_code ELSE 'OTHER' END,
       NULL,
       p.amount - COALESCE((SELECT sum(r.amount) FROM payment_events r
                            WHERE r.payment_id = p.id AND r.event_type = 'REFUND_RECORDED'), 0),
       p.currency,
       'OPEN',
       'CARD_NETWORK',
       NULL,
       NULL,
       'system:card-network-simulator',
       e.occurred_at,
       e.correlation_id,
       NULL,
       NULL,
       0,
       e.occurred_at
FROM payments p
JOIN payment_events e ON e.payment_id = p.id AND e.event_type = 'DISPUTE_OPENED' AND e.correlation_id LIKE 'seed-%'
WHERE p.status = 'DISPUTED'
  AND p.payment_ref ~ '^PAY-[0-9]{6}$'
  AND NOT EXISTS (SELECT 1 FROM dispute_cases c WHERE c.payment_id = p.id)
ON CONFLICT DO NOTHING;

INSERT INTO dispute_case_events (id, case_id, sequence_no, event_type, from_status, to_status, reason_code,
                                 actor_subject, actor_username, correlation_id, occurred_at, ledger_event_id)
SELECT md5('dispute-case-event-' || c.payment_ref || '-1')::uuid,
       c.id,
       1,
       'OPENED',
       NULL,
       'OPEN',
       c.reason_category,
       NULL,
       c.opened_by_username,
       c.correlation_id,
       c.opened_at,
       e.id
FROM dispute_cases c
JOIN payment_events e ON e.payment_id = c.payment_id AND e.event_type = 'DISPUTE_OPENED'
                     AND e.correlation_id = c.correlation_id
WHERE c.source = 'CARD_NETWORK'
  AND c.id = md5('dispute-case-' || c.payment_ref)::uuid
  AND NOT EXISTS (SELECT 1 FROM dispute_case_events x WHERE x.case_id = c.id)
ON CONFLICT DO NOTHING;
