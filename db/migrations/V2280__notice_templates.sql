SET lock_timeout = '5s';
-- IDC-003 (ADR 0018 section 5): Nabd-owned privacy notice templates per market and language. Platform reference table:
-- no tenant_id, read-only to the API role (new template versions ship as migrations / the control plane, never from a clinic).
-- No legal text is drafted here: every mandatory clause below is a PLACEHOLDER marker until the DPO supplies the wording
-- (legal_reviewed stays false and the owner screen and the public page say so). Replace by publishing a new template version.
CREATE TABLE notice_templates (
  key                text NOT NULL,                 -- market + law + version, e.g. IN-DPDP-1 (copied to privacy_notices.template_key)
  market             char(2) NOT NULL REFERENCES regions(code),
  language           text NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$'),
  version            int  NOT NULL CHECK (version >= 1),
  body_md            text NOT NULL,                 -- {{clinic_name}} and {{grievance_contact}} are the only editable blocks
  mandatory_sections jsonb NOT NULL,                -- ordered array of locked clause texts; each must appear verbatim, in order
  legal_reviewed     boolean NOT NULL DEFAULT false,
  published_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (key, language)
);

DO $$
DECLARE
  lang text;
  clauses jsonb;
BEGIN
  FOREACH lang IN ARRAY ARRAY['en', 'hi', 'kn', 'te'] LOOP
    clauses := to_jsonb(ARRAY[
      '[PENDING DPO WORDING IN-DPDP-1/' || lang || ' clause M1: personal data and purpose]',
      '[PENDING DPO WORDING IN-DPDP-1/' || lang || ' clause M2: rights and how to exercise them]',
      '[PENDING DPO WORDING IN-DPDP-1/' || lang || ' clause M3: withdrawal of consent]',
      '[PENDING DPO WORDING IN-DPDP-1/' || lang || ' clause M4: complaint to the regulator]']);
    INSERT INTO notice_templates (key, market, language, version, body_md, mandatory_sections)
    VALUES ('IN-DPDP-1', 'IN', lang, 1,
      '# {{clinic_name}}' || E'\n\n' ||
      (SELECT string_agg(c, E'\n\n') FROM jsonb_array_elements_text(clauses) c) || E'\n\n' ||
      '{{grievance_contact}}', clauses);
  END LOOP;
END $$;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON notice_templates FROM nabd_app;
    GRANT SELECT ON notice_templates TO nabd_app;
  END IF;
END $$;
