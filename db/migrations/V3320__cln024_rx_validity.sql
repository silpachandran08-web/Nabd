SET lock_timeout = '5s';
-- CLN-024: Rx validity and refill rules by drug class, and the per-line values a prescription carries.
--
-- rx_validity_rules is regional REFERENCE data versioned with the CLN-004 safety dictionary (V1210): no tenant_id (each
-- regional cell holds its own region), owned by the migrator, SELECT-only for nabd_app, written only by the dictionary
-- import below (one upload, one CSO approval, one activation for the dictionary and its rules). The rules in effect for a
-- region are those of the latest version AT OR BELOW the active dictionary version that carries rules, so a dictionary
-- version uploaded without rules keeps the previous rules, and a version with rules replaces the whole set.
-- A prescription line stores the values applied when it was signed (validity_days, refills_allowed), so activating a new
-- version changes only prescriptions signed afterwards.
--
-- class_id: a drug class id (drug master classes of a coded line; the dictionary's allergy classes matched in the text),
-- 'schedule:<flag>' (a coded product's drug master schedule flag), 'controlled' (the dictionary's controlled list) or
-- '*' (every other line). Several matches: the strictest value of each applies (PrescriptionService / RxValidityRules).
-- fixed: the rule's values apply regardless of the prescriber's input (single dispense, e.g. Schedule H1).
CREATE TABLE rx_validity_rules (
  region       text NOT NULL,
  version      int  NOT NULL,
  class_id     text NOT NULL CHECK (class_id ~ '^(\*|controlled|schedule:[A-Za-z0-9-]{1,32}|[a-z0-9][a-z0-9-]{0,63})$'),
  default_days int  NOT NULL CHECK (default_days BETWEEN 1 AND 365),
  max_days     int  NOT NULL CHECK (max_days BETWEEN 1 AND 365),
  max_refills  int  NOT NULL CHECK (max_refills BETWEEN 0 AND 5),
  fixed        boolean NOT NULL DEFAULT false,
  PRIMARY KEY (region, version, class_id),
  -- cascade only for the migrator / test fixtures that drop an unused draft version; nabd_app cannot delete either table
  FOREIGN KEY (region, version) REFERENCES safety_dictionary_versions (region, version) ON DELETE CASCADE,
  CONSTRAINT rx_validity_rules_default_within_max CHECK (default_days <= max_days),
  CONSTRAINT rx_validity_rules_fixed_is_single CHECK (NOT fixed OR (max_refills = 0 AND max_days = default_days))
);

-- validity_days: the line's validity from signing (NULL on a draft = the class default; NULL on a signed line = no rule
-- applied, as for every line signed before this migration). refills_allowed: dispenses after the first.
ALTER TABLE prescription_items ADD COLUMN validity_days integer CHECK (validity_days BETWEEN 1 AND 365);
ALTER TABLE prescription_items ADD COLUMN refills_allowed integer NOT NULL DEFAULT 0 CHECK (refills_allowed BETWEEN 0 AND 5);

-- The CLN-004 upload with the rules of that version. Same contract as the 4-argument import (V1210): a draft version, and
-- re-uploading identical content returns the existing version. The checksum covers the rules only when there are some, so
-- a bundle without rules keeps the checksum it had before CLN-024.
CREATE OR REPLACE FUNCTION safety_dictionary_import(p_region text, p_entries jsonb, p_labels jsonb, p_created_by text, p_rules jsonb)
  RETURNS TABLE (version_id uuid, version int, checksum text, status text, created boolean)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  v_rules jsonb := COALESCE(p_rules, '[]'::jsonb);
  v_checksum text := encode(sha256(convert_to((jsonb_build_object('entries', p_entries, 'labels', COALESCE(p_labels, '{}'::jsonb))
                     || CASE WHEN jsonb_array_length(v_rules) > 0 THEN jsonb_build_object('rxValidityRules', v_rules) ELSE '{}'::jsonb END)::text,
                     'UTF8')), 'hex');
  v_latest safety_dictionary_versions%ROWTYPE;
  v_id uuid;
  v_version int;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('safety_dictionary:' || p_region));
  SELECT * INTO v_latest FROM safety_dictionary_versions d WHERE d.region = p_region AND d.checksum = v_checksum
   ORDER BY d.version DESC LIMIT 1;
  IF FOUND THEN
    RETURN QUERY SELECT v_latest.id, v_latest.version, v_latest.checksum, v_latest.status, false;
    RETURN;
  END IF;
  SELECT COALESCE(max(d.version) + 1, 0) INTO v_version FROM safety_dictionary_versions d WHERE d.region = p_region;
  INSERT INTO safety_dictionary_versions (region, version, status, checksum, labels, created_by)
  VALUES (p_region, v_version, 'draft', v_checksum, COALESCE(p_labels, '{}'::jsonb), p_created_by) RETURNING id INTO v_id;
  INSERT INTO safety_dictionary_entries (version_id, kind, class_id, match_kind, value, lang, cross_reacts_to)
  SELECT v_id, x.e->>'kind', x.e->>'classId', x.e->>'matchKind', x.e->>'value', x.e->>'lang',
         ARRAY(SELECT jsonb_array_elements_text(COALESCE(x.e->'crossReactsTo', '[]'::jsonb)))
  FROM jsonb_array_elements(p_entries) WITH ORDINALITY AS x(e, n) ORDER BY x.n;
  INSERT INTO rx_validity_rules (region, version, class_id, default_days, max_days, max_refills, fixed)
  SELECT p_region, v_version, r->>'classId', (r->>'defaultDays')::int, (r->>'maxDays')::int, (r->>'maxRefills')::int,
         COALESCE((r->>'fixed')::boolean, false)
  FROM jsonb_array_elements(v_rules) r;
  RETURN QUERY SELECT v_id, v_version, v_checksum, 'draft'::text, true;
END $$;

REVOKE ALL ON FUNCTION safety_dictionary_import(text, jsonb, jsonb, text, jsonb) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON rx_validity_rules FROM nabd_app;
    GRANT SELECT ON rx_validity_rules TO nabd_app;
    GRANT EXECUTE ON FUNCTION safety_dictionary_import(text, jsonb, jsonb, text, jsonb) TO nabd_app;
  END IF;
END $$;

-- NEEDS CLINICAL REVIEW: PLACEHOLDER values taken from the CLN-024 story examples (RX-04 / B-48), attached to the active
-- IN v0 dictionary so the rules work before the CSO uploads a reviewed set. They are NOT clinically approved: the Clinical
-- Safety Officer must review them and replace them through the dictionary upload (a new version with its own rules).
-- Antibiotics = the antibiotic allergy classes of the IN v0 dictionary. The chronic class ids (antihypertensive,
-- antidiabetic, thyroid) are placeholders that only match a drug master whose KB uses those ids. No GCC rules are seeded:
-- controlled drugs there follow MOH/DHA rules that have not been supplied (no rule = no per-line validity, no refills).
INSERT INTO rx_validity_rules (region, version, class_id, default_days, max_days, max_refills, fixed) VALUES
  ('IN', 0, 'penicillin',             5,  5,  0, false),
  ('IN', 0, 'cephalosporin',          5,  5,  0, false),
  ('IN', 0, 'sulfonamide-antibiotic', 5,  5,  0, false),
  ('IN', 0, 'macrolide',              5,  5,  0, false),
  ('IN', 0, 'fluoroquinolone',        5,  5,  0, false),
  ('IN', 0, 'tetracycline',           5,  5,  0, false),
  ('IN', 0, 'aminoglycoside',         5,  5,  0, false),
  ('IN', 0, 'schedule:H1',            7,  7,  0, true),
  ('IN', 0, 'controlled',             7,  7,  0, true),
  ('IN', 0, 'antihypertensive',       30, 30, 2, false),
  ('IN', 0, 'antidiabetic',           30, 30, 2, false),
  ('IN', 0, 'thyroid',                30, 30, 2, false),
  ('IN', 0, '*',                      30, 30, 0, false);

SELECT nabd_adopt_definer_functions();
