SET lock_timeout = '5s';
-- CLN-019: regional drug master (one row per product = brand + strength + form) linked to ingredients and classes,
-- loaded from the licensed KB feed (CLN-018) as versioned imports. Regional REFERENCE data like the CLN-004 safety
-- dictionary: no tenant_id (each regional cell holds its own region), owned by the migrator, SELECT-only for nabd_app.
-- Content changes only through the SECURITY DEFINER import functions below (stage -> validate with dry-run diff ->
-- activate in ONE transaction), so a half-finished import can never be visible. A product retired in the KB stays
-- in drug_master with status 'retired' (still resolvable by id for existing prescriptions, hidden from search).

CREATE TABLE IF NOT EXISTS drug_classes (
  id   text PRIMARY KEY,
  name text NOT NULL CHECK (btrim(name) <> '')
);

CREATE TABLE IF NOT EXISTS ingredients (
  id         text PRIMARY KEY,
  name       text NOT NULL CHECK (btrim(name) <> ''),
  inn        text,
  synonyms   text[] NOT NULL DEFAULT '{}',
  class_ids  text[] NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS drug_master_imports (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  region        text NOT NULL CHECK (region IN ('IN', 'SA', 'AE', 'QA')),
  kind          text NOT NULL CHECK (kind IN ('full', 'delta')),
  dry_run       boolean NOT NULL DEFAULT true,
  status        text NOT NULL CHECK (status IN ('running', 'validated', 'active', 'failed', 'superseded')),
  feed_checksum text NOT NULL,
  kb            text,
  data_month    text,
  version       int,
  base_version  int,
  diff          jsonb,
  classes       jsonb NOT NULL DEFAULT '[]'::jsonb,
  error         text,
  cso_ack_ref   text,
  created_by    text,
  started_at    timestamptz NOT NULL DEFAULT now(),
  finished_at   timestamptz,
  activated_at  timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS drug_master_one_active ON drug_master_imports (region) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS drug_master_imports_feed ON drug_master_imports (region, kind, feed_checksum);

CREATE TABLE IF NOT EXISTS drug_master (
  id                 text PRIMARY KEY,
  region             text NOT NULL CHECK (region IN ('IN', 'SA', 'AE', 'QA')),
  kb_id              text NOT NULL,
  brand              text NOT NULL CHECK (btrim(brand) <> ''),
  strength           text,
  form               text,
  route              text,
  schedule           text[] NOT NULL DEFAULT '{}',
  pregnancy_category text,
  lactation_note     text,
  status             text NOT NULL CHECK (status IN ('active', 'retired')),
  version            int NOT NULL,
  import_id          uuid REFERENCES drug_master_imports (id),
  search_text        tsvector NOT NULL DEFAULT ''::tsvector,
  UNIQUE (region, kb_id)
);
CREATE INDEX IF NOT EXISTS drug_master_search ON drug_master USING gin (search_text);

CREATE TABLE IF NOT EXISTS drug_ingredients (
  drug_id       text NOT NULL REFERENCES drug_master (id),
  ingredient_id text NOT NULL REFERENCES ingredients (id),
  amount        numeric,
  unit          text,
  PRIMARY KEY (drug_id, ingredient_id)
);

-- One row per product of a running import: product = normalised feed record (jsonb). Never readable by nabd_app.
CREATE TABLE IF NOT EXISTS drug_master_stage (
  import_id uuid NOT NULL REFERENCES drug_master_imports (id) ON DELETE CASCADE,
  kb_id     text NOT NULL,
  product   jsonb NOT NULL,
  PRIMARY KEY (import_id, kb_id)
);

-- The comparable shape of a product (used by the diff and by activation). Ingredient display names are not part of it.
CREATE OR REPLACE FUNCTION drug_master_norm(p jsonb) RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT jsonb_build_object(
    'brand', p->>'brand', 'strength', p->>'strength', 'form', p->>'form', 'route', p->>'route',
    'pregnancyCategory', p->>'pregnancyCategory', 'lactationNote', p->>'lactationNote', 'status', p->>'status',
    'schedule', (SELECT COALESCE(jsonb_agg(s ORDER BY s), '[]'::jsonb) FROM jsonb_array_elements_text(COALESCE(p->'schedule', '[]'::jsonb)) s),
    'ingredients', (SELECT COALESCE(jsonb_agg(jsonb_build_object('id', i->>'id', 'amount', (i->>'amount')::numeric, 'unit', i->>'unit')
                                              ORDER BY i->>'id'), '[]'::jsonb)
                    FROM jsonb_array_elements(COALESCE(p->'ingredients', '[]'::jsonb)) i));
$$;

CREATE OR REPLACE FUNCTION drug_master_live_norm(p_id text) RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT drug_master_norm(jsonb_build_object(
    'brand', m.brand, 'strength', m.strength, 'form', m.form, 'route', m.route, 'pregnancyCategory', m.pregnancy_category,
    'lactationNote', m.lactation_note, 'status', m.status, 'schedule', to_jsonb(m.schedule),
    'ingredients', (SELECT COALESCE(jsonb_agg(jsonb_build_object('id', d.ingredient_id, 'amount', d.amount, 'unit', d.unit)), '[]'::jsonb)
                    FROM drug_ingredients d WHERE d.drug_id = m.id)))
  FROM drug_master m WHERE m.id = p_id;
$$;

-- Same feed (region, kind, checksum, dry-run flag) returns the existing non-failed import instead of a second one.
CREATE OR REPLACE FUNCTION drug_master_import_start(p_region text, p_kind text, p_dry_run boolean, p_checksum text, p_by text)
  RETURNS TABLE (import_id uuid, created boolean, status text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  e drug_master_imports%ROWTYPE;
  v_id uuid;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('drug_master:' || p_region));
  SELECT * INTO e FROM drug_master_imports i
   WHERE i.region = p_region AND i.kind = p_kind AND i.feed_checksum = p_checksum AND i.dry_run = p_dry_run AND i.status <> 'failed'
   ORDER BY i.started_at DESC LIMIT 1;
  IF FOUND THEN
    RETURN QUERY SELECT e.id, false, e.status;
    RETURN;
  END IF;
  INSERT INTO drug_master_imports (region, kind, dry_run, status, feed_checksum, created_by)
  VALUES (p_region, p_kind, p_dry_run, 'running', p_checksum, p_by) RETURNING id INTO v_id;
  RETURN QUERY SELECT v_id, true, 'running'::text;
END $$;

CREATE OR REPLACE FUNCTION drug_master_stage_batch(p_import uuid, p_rows jsonb) RETURNS int
  LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  WITH ins AS (
    INSERT INTO drug_master_stage (import_id, kb_id, product)
    SELECT p_import, r->>'kbId', r FROM jsonb_array_elements(p_rows) r
     WHERE EXISTS (SELECT 1 FROM drug_master_imports i WHERE i.id = p_import AND i.status = 'running')
    RETURNING 1)
  SELECT count(*)::int FROM ins;
$$;

CREATE OR REPLACE FUNCTION drug_master_fail(p_import uuid, p_error text) RETURNS void
  LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  WITH d AS (DELETE FROM drug_master_stage WHERE import_id = p_import)
  UPDATE drug_master_imports SET status = 'failed', error = left(p_error, 500), finished_at = now()
   WHERE id = p_import AND status IN ('running', 'validated');
$$;

-- Computes the dry-run diff of the staged feed against the region's live products and marks the import validated.
-- A dry-run import drops its staged rows (it can never be activated).
CREATE OR REPLACE FUNCTION drug_master_validate(p_import uuid, p_kb text, p_month text, p_classes jsonb) RETURNS jsonb
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  i drug_master_imports%ROWTYPE;
  v_diff jsonb;
  v_base int;
BEGIN
  SELECT * INTO i FROM drug_master_imports WHERE id = p_import AND status = 'running' FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'import % is not running', p_import;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('drug_master:' || i.region));
  SELECT version INTO v_base FROM drug_master_imports WHERE region = i.region AND status = 'active';
  WITH s AS (
    SELECT st.kb_id, st.product->>'status' AS status, drug_master_norm(st.product) AS n FROM drug_master_stage st WHERE st.import_id = p_import),
  l AS (
    SELECT m.kb_id, m.status, drug_master_live_norm(m.id) AS n FROM drug_master m WHERE m.region = i.region),
  j AS (
    SELECT COALESCE(s.kb_id, l.kb_id) AS kb_id, s.kb_id IS NOT NULL AS in_feed, s.status AS s_status, l.kb_id IS NOT NULL AS in_live,
           l.status AS l_status, s.n AS sn, l.n AS ln
      FROM s FULL JOIN l ON l.kb_id = s.kb_id),
  c AS (
    SELECT kb_id,
      CASE WHEN in_feed AND NOT in_live AND s_status = 'active' THEN 'added'
           WHEN in_feed AND in_live AND l_status = 'active' AND s_status = 'retired' THEN 'retired'
           WHEN NOT in_feed AND in_live AND l_status = 'active' AND i.kind = 'full' THEN 'retired'
           WHEN in_feed AND in_live AND s_status = 'active' AND sn <> ln THEN 'changed' END AS what,
      (in_feed AND in_live AND s_status = 'active' AND sn <> ln
        AND (sn->'schedule' <> ln->'schedule' OR sn->'pregnancyCategory' <> ln->'pregnancyCategory' OR sn->'ingredients' <> ln->'ingredients')) AS safety
      FROM j)
  SELECT jsonb_build_object(
      'added', count(*) FILTER (WHERE what = 'added'), 'changed', count(*) FILTER (WHERE what = 'changed'),
      'retired', count(*) FILTER (WHERE what = 'retired'), 'safetyRelevant', count(*) FILTER (WHERE safety),
      'samples', jsonb_build_object(
        'added', COALESCE((SELECT jsonb_agg(kb_id) FROM (SELECT kb_id FROM c WHERE what = 'added' ORDER BY kb_id LIMIT 20) a), '[]'::jsonb),
        'changed', COALESCE((SELECT jsonb_agg(kb_id) FROM (SELECT kb_id FROM c WHERE what = 'changed' ORDER BY kb_id LIMIT 20) a), '[]'::jsonb),
        'retired', COALESCE((SELECT jsonb_agg(kb_id) FROM (SELECT kb_id FROM c WHERE what = 'retired' ORDER BY kb_id LIMIT 20) a), '[]'::jsonb)))
    INTO v_diff FROM c;
  IF i.dry_run THEN
    DELETE FROM drug_master_stage WHERE import_id = p_import;
  END IF;
  UPDATE drug_master_imports SET status = 'validated', diff = v_diff, kb = p_kb, data_month = p_month, base_version = v_base,
         classes = COALESCE(p_classes, '[]'::jsonb), finished_at = now() WHERE id = p_import;
  RETURN v_diff;
END $$;

-- Activation = the version switch: products, ingredients, classes and the import status change in this one transaction.
CREATE OR REPLACE FUNCTION drug_master_activate(p_import uuid, p_cso text)
  RETURNS TABLE (outcome text, version int, added int, changed int, retired int)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  i drug_master_imports%ROWTYPE;
  v_ver int;
  v_cur int;
  v_ids text[];
BEGIN
  SELECT * INTO i FROM drug_master_imports WHERE id = p_import;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'not_found'::text, NULL::int, NULL::int, NULL::int, NULL::int;
    RETURN;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('drug_master:' || i.region));
  SELECT * INTO i FROM drug_master_imports WHERE id = p_import FOR UPDATE;
  IF i.status = 'active' THEN
    RETURN QUERY SELECT 'already_active'::text, i.version, (i.diff->>'added')::int, (i.diff->>'changed')::int, (i.diff->>'retired')::int;
    RETURN;
  END IF;
  IF i.status <> 'validated' THEN
    RETURN QUERY SELECT 'not_validated'::text, NULL::int, NULL::int, NULL::int, NULL::int;
    RETURN;
  END IF;
  IF i.dry_run THEN
    RETURN QUERY SELECT 'dry_run'::text, NULL::int, NULL::int, NULL::int, NULL::int;
    RETURN;
  END IF;
  IF (i.diff->>'safetyRelevant')::int > 0 AND (p_cso IS NULL OR btrim(p_cso) = '') THEN
    RETURN QUERY SELECT 'cso_required'::text, NULL::int, NULL::int, NULL::int, NULL::int;
    RETURN;
  END IF;
  SELECT version INTO v_cur FROM drug_master_imports WHERE region = i.region AND status = 'active';
  IF v_cur IS DISTINCT FROM i.base_version THEN
    RETURN QUERY SELECT 'stale'::text, NULL::int, NULL::int, NULL::int, NULL::int;
    RETURN;
  END IF;
  SELECT COALESCE(max(x.version), 0) + 1 INTO v_ver FROM drug_master_imports x WHERE x.region = i.region AND x.status IN ('active', 'superseded');

  INSERT INTO drug_classes (id, name) SELECT c->>'id', c->>'name' FROM jsonb_array_elements(i.classes) c
  ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
  INSERT INTO ingredients (id, name, inn, synonyms, class_ids)
  SELECT DISTINCT ON (g->>'id') g->>'id', g->>'name', g->>'inn',
         ARRAY(SELECT jsonb_array_elements_text(COALESCE(g->'synonyms', '[]'::jsonb))),
         ARRAY(SELECT jsonb_array_elements_text(COALESCE(g->'classIds', '[]'::jsonb)))
    FROM drug_master_stage st, jsonb_array_elements(COALESCE(st.product->'ingredients', '[]'::jsonb)) g WHERE st.import_id = p_import
  ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, inn = EXCLUDED.inn, synonyms = EXCLUDED.synonyms, class_ids = EXCLUDED.class_ids;

  SELECT array_agg(lower(i.region) || '-' || st.kb_id) INTO v_ids
    FROM drug_master_stage st LEFT JOIN drug_master m ON m.id = lower(i.region) || '-' || st.kb_id
   WHERE st.import_id = p_import
     AND st.product->>'status' = 'active'
     AND (m.id IS NULL OR drug_master_norm(st.product) <> drug_master_live_norm(m.id));
  v_ids := COALESCE(v_ids, '{}');

  INSERT INTO drug_master (id, region, kb_id, brand, strength, form, route, schedule, pregnancy_category, lactation_note, status, version, import_id)
  SELECT lower(i.region) || '-' || st.kb_id, i.region, st.kb_id, st.product->>'brand', st.product->>'strength', st.product->>'form',
         st.product->>'route', ARRAY(SELECT jsonb_array_elements_text(COALESCE(st.product->'schedule', '[]'::jsonb))),
         st.product->>'pregnancyCategory', st.product->>'lactationNote', st.product->>'status', v_ver, p_import
    FROM drug_master_stage st WHERE st.import_id = p_import AND (lower(i.region) || '-' || st.kb_id) = ANY (v_ids)
  ON CONFLICT (id) DO UPDATE SET brand = EXCLUDED.brand, strength = EXCLUDED.strength, form = EXCLUDED.form, route = EXCLUDED.route,
         schedule = EXCLUDED.schedule, pregnancy_category = EXCLUDED.pregnancy_category, lactation_note = EXCLUDED.lactation_note,
         status = EXCLUDED.status, version = EXCLUDED.version, import_id = EXCLUDED.import_id;
  DELETE FROM drug_ingredients WHERE drug_id = ANY (v_ids);
  INSERT INTO drug_ingredients (drug_id, ingredient_id, amount, unit)
  SELECT lower(i.region) || '-' || st.kb_id, g->>'id', (g->>'amount')::numeric, g->>'unit'
    FROM drug_master_stage st, jsonb_array_elements(COALESCE(st.product->'ingredients', '[]'::jsonb)) g
   WHERE st.import_id = p_import AND (lower(i.region) || '-' || st.kb_id) = ANY (v_ids);
  UPDATE drug_master m SET search_text = to_tsvector('simple',
      m.brand || ' ' || COALESCE(m.strength, '') || ' ' || COALESCE(m.form, '') || ' ' ||
      COALESCE((SELECT string_agg(g.name || ' ' || COALESCE(g.inn, '') || ' ' || array_to_string(g.synonyms, ' '), ' ')
                  FROM drug_ingredients d JOIN ingredients g ON g.id = d.ingredient_id WHERE d.drug_id = m.id), ''))
   WHERE m.id = ANY (v_ids);
  -- retiring only flips the status: the product keeps its strength, form and ingredients for the prescriptions that used it
  UPDATE drug_master m SET status = 'retired', version = v_ver, import_id = p_import
   WHERE m.region = i.region AND m.status = 'active'
     AND (EXISTS (SELECT 1 FROM drug_master_stage st WHERE st.import_id = p_import AND st.kb_id = m.kb_id AND st.product->>'status' = 'retired')
          OR (i.kind = 'full' AND NOT EXISTS (SELECT 1 FROM drug_master_stage st WHERE st.import_id = p_import AND st.kb_id = m.kb_id)));
  DELETE FROM drug_master_stage WHERE import_id = p_import;
  UPDATE drug_master_imports SET status = 'superseded' WHERE region = i.region AND status = 'active';
  UPDATE drug_master_imports SET status = 'active', version = v_ver, activated_at = now(), cso_ack_ref = NULLIF(btrim(p_cso), '') WHERE id = p_import;
  RETURN QUERY SELECT 'activated'::text, v_ver, (i.diff->>'added')::int, (i.diff->>'changed')::int, (i.diff->>'retired')::int;
END $$;

REVOKE ALL ON FUNCTION drug_master_import_start(text, text, boolean, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_master_stage_batch(uuid, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_master_fail(uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_master_validate(uuid, text, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION drug_master_activate(uuid, text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON drug_classes, ingredients, drug_master_imports, drug_master, drug_ingredients, drug_master_stage FROM nabd_app;
    REVOKE SELECT ON drug_master_stage FROM nabd_app;
    GRANT SELECT ON drug_classes, ingredients, drug_master_imports, drug_master, drug_ingredients TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_master_import_start(text, text, boolean, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_master_stage_batch(uuid, jsonb) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_master_fail(uuid, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_master_validate(uuid, text, text, jsonb) TO nabd_app;
    GRANT EXECUTE ON FUNCTION drug_master_activate(uuid, text) TO nabd_app;
  END IF;
END $$;

SELECT nabd_adopt_definer_functions();
