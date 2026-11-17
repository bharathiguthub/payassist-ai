-- SYNTHETIC DATA ONLY - generated deterministically, contains no real people, merchants or payments.
-- Loaded only by the 'dev' profile (and tests) via the classpath:db/seed Flyway location.
-- Emails use the reserved example.com domain (RFC 2606). IDs are derived from md5() so re-runs are idempotent.

INSERT INTO customers (id, customer_ref, full_name, email, country_code, created_at)
SELECT md5('customer-' || i)::uuid,
       'CUS-' || lpad(i::text, 5, '0'),
       (ARRAY['Avery', 'Jordan', 'Riley', 'Morgan', 'Casey', 'Quinn', 'Harper', 'Rowan', 'Skyler', 'Emerson'])[1 + (i % 10)]
           || ' ' ||
       (ARRAY['Testwell', 'Sampleton', 'Demoford', 'Mockridge', 'Synthfield', 'Placeholder', 'Fakeham', 'Exampleby'])[1 + ((i / 10) % 8)],
       'customer' || lpad(i::text, 5, '0') || '@example.com',
       (ARRAY['GB', 'US', 'IN', 'DE', 'FR', 'SG', 'AU', 'CA'])[1 + (i % 8)],
       timestamptz '2025-01-01 00:00:00+00' + make_interval(days => i * 5)
FROM generate_series(1, 40) AS s(i)
ON CONFLICT (customer_ref) DO NOTHING;

INSERT INTO payments (id, payment_ref, customer_id, amount, currency, status, payment_method,
                      merchant_name, failure_reason, created_at, updated_at)
SELECT md5('payment-' || i)::uuid,
       'PAY-' || lpad(i::text, 6, '0'),
       md5('customer-' || (1 + (i % 40)))::uuid,
       round(5 + ((i * 7919) % 250000) / 100.0, 2),
       (ARRAY['GBP', 'USD', 'INR', 'EUR'])[1 + (i % 4)],
       g.status,
       (ARRAY['CARD', 'BANK_TRANSFER', 'WALLET'])[1 + (i % 3)],
       (ARRAY['Synthetic Electronics Ltd', 'Demo Travel Co', 'Sample Groceries Inc',
              'Example Streaming Service', 'Mock Fashion House', 'Test Utilities plc'])[1 + (i % 6)],
       CASE WHEN g.status = 'FAILED'
            THEN (ARRAY['INSUFFICIENT_FUNDS', 'ISSUER_DECLINED', 'AUTHENTICATION_FAILED'])[1 + (i % 3)]
       END,
       g.created_at,
       g.created_at + interval '2 hours'
FROM generate_series(1, 400) AS s(i)
CROSS JOIN LATERAL (
    SELECT (ARRAY['SETTLED', 'SETTLED', 'SETTLED', 'SETTLED', 'CAPTURED', 'CAPTURED', 'AUTHORIZED',
                  'PENDING', 'FAILED', 'FAILED', 'REFUNDED', 'DISPUTED'])[1 + ((i * 31) % 12)] AS status,
           timestamptz '2026-01-01 00:00:00+00'
               + make_interval(days => (i * 137) % 300, mins => (i * 53) % 1440) AS created_at
) AS g
ON CONFLICT (payment_ref) DO NOTHING;
