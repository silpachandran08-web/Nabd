SET lock_timeout = '5s';
-- PHL-002: every stocked medicine carries its statutory class. Rules are reference data (no tenant_id, SELECT-only
-- for nabd_app, changed by migration + PR review; seeded values await Clinical Safety Officer sign-off, and
-- retention_years is left NULL until the CSO supplies the statutory figure). charge_catalogue.is_rx stays and is
-- derived from the class by trigger, so PHL-001 keeps working.
CREATE TABLE pharmacy_schedule_rules (
  market                    text NOT NULL CHECK (market IN ('IN', 'SA', 'AE', 'QA')),
  schedule_class            text NOT NULL,
  label                     text NOT NULL,
  requires_rx               boolean NOT NULL,
  register_type             text,
  duplicate_rx_copy         boolean NOT NULL DEFAULT false,
  retention_years           int CHECK (retention_years > 0),
  refill_self_serve_allowed boolean NOT NULL,
  drug_master_flag          text,
  PRIMARY KEY (market, schedule_class)
);
INSERT INTO pharmacy_schedule_rules (market, schedule_class, label, requires_rx, register_type, duplicate_rx_copy, refill_self_serve_allowed, drug_master_flag) VALUES
  ('IN', 'OTC',                 'Over the counter',        false, NULL,                 false, true,  NULL),
  ('IN', 'IN_H',                'Schedule H',              true,  NULL,                 false, true,  'H'),
  ('IN', 'IN_H1',               'Schedule H1',             true,  'IN_H1',              false, false, 'H1'),
  ('IN', 'IN_X',                'Schedule X',              true,  'IN_X',               true,  false, 'X'),
  ('IN', 'IN_NDPS',             'NDPS',                    true,  'IN_NDPS',            false, false, 'NDPS'),
  ('SA', 'OTC',                 'Over the counter',        false, NULL,                 false, true,  NULL),
  ('SA', 'SA_RX',               'Prescription only',       true,  NULL,                 false, true,  NULL),
  ('SA', 'SA_SEMI_CONTROLLED',  'SFDA semi-controlled',    true,  NULL,                 false, false, NULL),
  ('SA', 'SA_CONTROLLED',       'SFDA controlled',         true,  'SA_CONTROLLED',      false, false, 'SFDA-controlled'),
  ('AE', 'OTC',                 'Over the counter',        false, NULL,                 false, true,  NULL),
  ('AE', 'AE_RX',               'Prescription only',       true,  NULL,                 false, true,  NULL),
  ('AE', 'AE_SCD',              'Semi-controlled drug',    true,  NULL,                 false, false, NULL),
  ('AE', 'AE_CD',               'Controlled drug',         true,  'AE_CD',              false, false, NULL),
  ('QA', 'OTC',                 'Over the counter',        false, NULL,                 false, true,  NULL),
  ('QA', 'QA_RX',               'Prescription only',       true,  NULL,                 false, true,  NULL),
  ('QA', 'QA_CD',               'Controlled drug',         true,  'QA_CD',              false, false, NULL);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON pharmacy_schedule_rules FROM nabd_app;
    GRANT SELECT ON pharmacy_schedule_rules TO nabd_app;
  END IF;
END $$;

CREATE TABLE pharmacy_item_profiles (
  item_id        uuid PRIMARY KEY REFERENCES charge_catalogue (id),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  schedule_class text NOT NULL CHECK (schedule_class IN ('OTC','IN_H','IN_H1','IN_X','IN_NDPS','SA_RX','SA_SEMI_CONTROLLED',
                                                          'SA_CONTROLLED','AE_RX','AE_SCD','AE_CD','QA_RX','QA_CD')),
  generic_name   text,
  pack_size      int CHECK (pack_size > 0),
  unit           text,
  in_house       boolean NOT NULL DEFAULT true,   -- PHL-004: false = printed 'buy outside', never offered at checkout
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pharmacy_item_profiles_class ON pharmacy_item_profiles (tenant_id, schedule_class);
CREATE TRIGGER trg_pharmacy_item_profiles_updated_at BEFORE UPDATE ON pharmacy_item_profiles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Backfill (one row per stocked item): Rx -> the market's default Rx class, else OTC. Each tenant is scoped like the API does
-- (RLS is forced and the migrator has no BYPASSRLS). The sync trigger is created afterwards.
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id, region_code FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    INSERT INTO pharmacy_item_profiles (item_id, tenant_id, schedule_class)
    SELECT c.id, c.tenant_id,
           CASE WHEN COALESCE(c.is_rx, false)
                THEN CASE trim(t.region_code) WHEN 'SA' THEN 'SA_RX' WHEN 'AE' THEN 'AE_RX' WHEN 'QA' THEN 'QA_RX' ELSE 'IN_H' END
                ELSE 'OTC' END
      FROM charge_catalogue c WHERE c.tenant_id = t.id AND c.stock_qty IS NOT NULL;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

CREATE FUNCTION pharmacy_profile_sync_rx() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE charge_catalogue SET is_rx = COALESCE((SELECT bool_or(r.requires_rx) FROM pharmacy_schedule_rules r
                                                 WHERE r.schedule_class = NEW.schedule_class), false)
   WHERE id = NEW.item_id AND tenant_id = NEW.tenant_id;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_pharmacy_profile_sync_rx AFTER INSERT OR UPDATE OF schedule_class ON pharmacy_item_profiles
  FOR EACH ROW EXECUTE FUNCTION pharmacy_profile_sync_rx();

SELECT enforce_tenant_rls('pharmacy_item_profiles');

-- Past sales keep the class they were sold under.
ALTER TABLE invoice_line_items ADD COLUMN schedule_class text;
