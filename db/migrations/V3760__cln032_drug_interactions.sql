SET lock_timeout = '5s';
-- CLN-032: the drug-drug interaction and duplicate-therapy knowledge base read by the Rx pad checks.
--
-- Regional REFERENCE data like the CLN-019 drug master (V2180): no tenant_id (each regional cell holds its own region),
-- owned by the migrator, SELECT-only for nabd_app, written only through the SECURITY DEFINER import functions below.
-- THIS MIGRATION SHIPS NO CONTENT: the pairs, the non-additive classes and their severities are licensed clinical
-- knowledge and arrive later through the loader (DrugInteractionImportService). Until a version is ACTIVE for a region
-- the Rx pad runs no interaction check at all for that region (it is "not checked", never "no interactions").
--
-- A version (region, kb_version) is loaded as a whole, is invisible to prescribers until it is activated with a Clinical
-- Safety Officer reference, and stays in the table afterwards (status superseded), so going back to an earlier version is
-- the same activation call. Ingredients and classes are the drug master's ids (ingredients.id, drug_classes.id).

CREATE TABLE drug_interaction_versions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  region        text NOT NULL CHECK (region IN ('IN', 'SA', 'AE', 'QA')),
  kb_version    int  NOT NULL CHECK (kb_version >= 1),
  kb            text NOT NULL CHECK (btrim(kb) <> ''),
  data_month    text NOT NULL CHECK (data_month ~ '^\d{4}-(0[1-9]|1[0-2])$'),
  -- severity of "the same ingredient twice": the knowledge base states it, the engine has no value of its own
  duplicate_ingredient_severity text NOT NULL CHECK (duplicate_ingredient_severity IN ('contraindicated', 'major', 'moderate', 'minor')),
  status        text NOT NULL CHECK (status IN ('loading', 'validated', 'active', 'superseded')),
  feed_checksum text NOT NULL,
  cso_ack_ref   text,
  created_by    text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  activated_at  timestamptz,
  UNIQUE (region, kb_version),
  CONSTRAINT drug_interaction_versions_active_is_acknowledged CHECK (status <> 'active' OR btrim(COALESCE(cso_ack_ref, '')) <> '')
);
CREATE UNIQUE INDEX drug_interaction_one_active ON drug_interaction_versions (region) WHERE status = 'active';

-- One row per unordered ingredient pair: ingredient_a < ingredient_b (byte order, so the rule does not depend on the
-- database collation). The primary key serves the check's lookup (region, version, a = ANY, b = ANY).
CREATE TABLE drug_interactions (
  region       text NOT NULL,
  kb_version   int  NOT NULL,
  ingredient_a text NOT NULL REFERENCES ingredients (id),
  ingredient_b text NOT NULL REFERENCES ingredients (id),
  severity     text NOT NULL CHECK (severity IN ('contraindicated', 'major', 'moderate', 'minor')),
  mechanism    text,
  management   text,
  source_ref   text,
  PRIMARY KEY (region, kb_version, ingredient_a, ingredient_b),
  -- cascade only for the migrator / test fixtures that drop a version; nabd_app cannot delete either table
  FOREIGN KEY (region, kb_version) REFERENCES drug_interaction_versions (region, kb_version) ON DELETE CASCADE,
  CONSTRAINT drug_interactions_ordered CHECK (ingredient_a COLLATE "C" < ingredient_b COLLATE "C")
);

-- Therapeutic classes the knowledge base marks as non-additive: two different ingredients of such a class on one patient
-- are duplicate therapy. A pair that spans two classes (the knowledge base decides which) is modelled as a class both
-- ingredients belong to (ingredients.class_ids holds several).
CREATE TABLE drug_duplicate_classes (
  region     text NOT NULL,
  kb_version int  NOT NULL,
  class_id   text NOT NULL REFERENCES drug_classes (id),
  severity   text NOT NULL CHECK (severity IN ('contraindicated', 'major', 'moderate', 'minor')),
  source_ref text,
  PRIMARY KEY (region, kb_version, class_id),
  FOREIGN KEY (region, kb_version) REFERENCES drug_interaction_versions (region, kb_version) ON DELETE CASCADE
);

-- The same feed file (checksum) for a region returns the version it already produced instead of a second one.
CREATE OR REPLACE FUNCTION drug_interaction_import_start(p_region text, p_kb text, p_month text, p_duplicate_severity text,
                                                         p_checksum text, p_by text)
  RETURNS TABLE (import_id uuid, kb_version int, created boolean, status text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  e drug_interaction_versions%ROWTYPE;
  v_id uuid;
  v_ver int;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('drug_interactions:' || p_region));
  SELECT * INTO e FROM drug_interaction_versions v WHERE v.region = p_region AND v.feed_checksum = p_checksum
   ORDER BY v.kb_version DESC LIMIT 1;
  IF FOUND THEN
    RETURN QUERY SELECT e.id, e.kb_version, false, e.status;
    RETURN;
  END IF;
  SELECT COALESCE(max(v.kb_version), 0) + 1 INTO v_ver FROM drug_interaction_versions v WHERE v.region = p_region;
  INSERT INTO drug_interaction_versions (region, kb_version, kb, data_month, duplicate_ingredient_severity, status, feed_checksum, created_by)
  VALUES (p_region, v_ver, p_kb, p_month, p_duplicate_severity, 'loading', p_checksum, p_by) RETURNING id INTO v_id;
  RETURN QUERY SELECT v_id, v_ver, true, 'loading'::text;
END $$;

-- Rows: {"type":"interaction","a","b","severity","mechanism","management","sourceRef"} or
-- {"type":"duplicate-class","classId","severity","sourceRef"}. Only a version that is still loading takes rows.
CREATE OR REPLACE FUNCTION drug_interaction_import_batch(p_import uuid, p_rows jsonb) RETURNS int
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v drug_interaction_versions%ROWTYPE;
  n int;
  m int;
BEGIN
  SELECT * INTO v FROM drug_interaction_versions WHERE id = p_import AND status = 'loading' FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'interaction import % is not loading', p_import;
  END IF;
  INSERT INTO drug_interactions (region, kb_version, ingredient_a, ingredient_b, severity, mechanism, management, source_ref)
  SELECT v.region, v.kb_version, least((r->>'a') COLLATE "C", (r->>'b') COLLATE "C"), greatest((r->>'a') COLLATE "C", (r->>'b') COLLATE "C"),
         r->>'severity', r->>'mechanism', r->>'management', r->>'sourceRef'
    FROM jsonb_array_elements(p_rows) r WHERE r->>'type' = 'interaction';
  GET DIAGNOSTICS n = ROW_COUNT;
  INSERT INTO drug_duplicate_classes (region, kb_version, class_id, severity, source_ref)
  SELECT v.region, v.kb_version, r->>'classId', r->>'severity', r->>'sourceRef'
    FROM jsonb_array_elements(p_rows) r WHERE r->>'type' = 'duplicate-class';
  GET DIAGNOSTICS m = ROW_COUNT;
  RETURN n + m;
END $$;

-- Closes the load. An EMPTY version is refused: an active version with nothing in it would make the pad look checked.
CREATE OR REPLACE FUNCTION drug_interaction_import_finish(p_import uuid)
  RETURNS TABLE (pairs int, classes int)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  v drug_interaction_versions%ROWTYPE;
  v_pairs int;
  v_classes int;
BEGIN
  SELECT * INTO v FROM drug_interaction_versions WHERE id = p_import AND status = 'loading' FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'interaction import % is not loading', p_import;
  END IF;
  SELECT count(*)::int INTO v_pairs FROM drug_interactions d WHERE d.region = v.region AND d.kb_version = v.kb_version;
  SELECT count(*)::int INTO v_classes FROM drug_duplicate_classes d WHERE d.region = v.region AND d.kb_version = v.kb_version;
  IF v_pairs + v_classes = 0 THEN
    RAISE EXCEPTION 'interaction import % is empty', p_import;
  END IF;
  UPDATE drug_interaction_versions SET status = 'validated' WHERE id = p_import;
  RETURN QUERY SELECT v_pairs, v_classes;
END $$;

-- The version switch: one transaction, always with a Clinical Safety Officer reference (every version is clinical content).
-- A superseded version can be activated again (roll back to it).
CREATE OR REPLACE FUNCTION drug_interaction_activate(p_import uuid, p_cso text)
  RETURNS TABLE (outcome text, region text, kb_version int)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  v drug_interaction_versions%ROWTYPE;
BEGIN
  SELECT * INTO v FROM drug_interaction_versions WHERE id = p_import;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'not_found'::text, NULL::text, NULL::int;
    RETURN;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('drug_interactions:' || v.region));
  SELECT * INTO v FROM drug_interaction_versions WHERE id = p_import FOR UPDATE;
  IF v.status = 'active' THEN
    RETURN QUERY SELECT 'already_active'::text, v.region, v.kb_version;
    RETURN;
  END IF;
  IF v.status NOT IN ('validated', 'superseded') THEN
    RETURN QUERY SELECT 'not_validated'::text, v.region, v.kb_version;
    RETURN;
  END IF;
  IF p_cso IS NULL OR btrim(p_cso) = '' THEN
    RETURN QUERY SELECT 'cso_required'::text, v.region, v.kb_version;
    RETURN;
  END IF;
  UPDATE drug_interaction_versions SET status = 'superseded' WHERE region = v.region AND status = 'active';
  UPDATE drug_interaction_versions SET status = 'active', activated_at = now(), cso_ack_ref = btrim(p_cso) WHERE id = p_import;
  RETURN QUERY SELECT 'activated'::text, v.region, v.kb_version;
END $$;

REVOKE ALL ON FUNCTION drug_interaction_import_start(text, text, text, text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_interaction_import_batch(uuid, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_interaction_import_finish(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_interaction_activate(uuid, text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON drug_interaction_versions, drug_interactions, drug_duplicate_classes FROM nabd_app;
    GRANT SELECT ON drug_interaction_versions, drug_interactions, drug_duplicate_classes TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_interaction_import_start(text, text, text, text, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_interaction_import_batch(uuid, jsonb) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_interaction_import_finish(uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_interaction_activate(uuid, text) TO nabd_app;
  END IF;
END $$;

SELECT nabd_adopt_definer_functions();
