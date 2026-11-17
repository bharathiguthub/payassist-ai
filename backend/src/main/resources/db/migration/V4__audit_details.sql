-- PayAssist AI - Phase 2, milestone 3: structured audit details.
-- Backward compatible: nullable column; existing rows keep NULL. The append-only trigger from V3 still applies.
--
-- details holds non-sensitive context of an audited action as a flat JSON object of strings, e.g.
-- {"decision": "ELIGIBLE", "policyVersion": "...", "reasonCodes": "WITHIN_POLICY"}.
-- Never personal data (names, emails, free text): references, codes, amounts and versions only.
ALTER TABLE audit_events ADD COLUMN details JSONB;

ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_details_object
    CHECK (details IS NULL OR jsonb_typeof(details) = 'object');
