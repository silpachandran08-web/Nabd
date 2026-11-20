-- FEF-041: the booking funnel by language, so non-English completion can be compared with English over the pilot (target >= 90%).
-- lang is the patient's chosen conversation language (a two-letter code, as in contact_languages): not PHI. NULL until the patient chooses.
ALTER TABLE funnel_steps ADD COLUMN lang text CHECK (lang IN ('en', 'hi', 'kn', 'te', 'ar'));

-- One row per clinic and language: conversations that reached the booking menu (started, as in docs/gates/phase1/whatsapp_booking.sql) and those that reached DONE. security_invoker: the caller's
-- tenant policy on funnel_steps still decides which clinic's rows are counted.
CREATE VIEW funnel_completion_by_lang WITH (security_invoker = true) AS
SELECT tenant_id, lang, count(*) AS started, count(*) FILTER (WHERE done) AS completed
  FROM (SELECT tenant_id, conversation_id, max(lang) AS lang, bool_or(to_state = 'DONE') AS done
          FROM funnel_steps GROUP BY tenant_id, conversation_id HAVING bool_or(to_state = 'MENU')) c
 GROUP BY tenant_id, lang;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT ON funnel_completion_by_lang TO edge_app;
    END IF;
END $$;
