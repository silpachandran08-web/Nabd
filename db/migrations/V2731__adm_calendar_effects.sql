SET lock_timeout = '5s';
-- ADM-028: what a holiday does (closed / short hours / open), where it came from, the platform's holiday templates and quiet hours.
-- clinic_holidays keeps its per-command RLS (V150, V2521); nothing about its policies changes here.
ALTER TABLE clinic_holidays ADD COLUMN effect text NOT NULL DEFAULT 'closed';
ALTER TABLE clinic_holidays ADD COLUMN short_hours jsonb;
ALTER TABLE clinic_holidays ADD COLUMN source_template text;
ALTER TABLE clinic_holidays ADD CONSTRAINT chk_clinic_holidays_effect CHECK (effect IN ('closed', 'short', 'open')) NOT VALID;
ALTER TABLE clinic_holidays VALIDATE CONSTRAINT chk_clinic_holidays_effect;
ALTER TABLE clinic_holidays ADD CONSTRAINT chk_clinic_holidays_short_hours CHECK ((effect = 'short') = (short_hours IS NOT NULL)) NOT VALID;
ALTER TABLE clinic_holidays VALIDATE CONSTRAINT chk_clinic_holidays_short_hours;

-- Platform reference data (no tenant_id, SELECT-only for the API role); rows ship as migrations after a gazette review each December.
-- kind 'national' rows belong to the whole market (code = market, region_code NULL) and are applied with every template of that market.
CREATE TABLE holiday_templates (
  code          text NOT NULL,
  market        text NOT NULL,
  region_code   text,
  year          int  NOT NULL,
  holiday_date  date NOT NULL,
  name          text NOT NULL,
  kind          text NOT NULL CHECK (kind IN ('national', 'state')),
  source        text NOT NULL,
  template_name text NOT NULL,
  PRIMARY KEY (code, year, holiday_date)
);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON holiday_templates FROM nabd_app;
    GRANT SELECT ON holiday_templates TO nabd_app;
  END IF;
END $$;
-- Fixed-date national days only (the same date every year). State and festival dates come from each state's gazette and are
-- added by a later migration once reviewed: Needs external action (platform team, December review).
INSERT INTO holiday_templates (code, market, region_code, year, holiday_date, name, kind, source, template_name)
SELECT 'IN', 'IN', NULL, y, make_date(y, m, d), n, 'national', 'India national holidays, fixed dates ' || y, 'India national holidays'
FROM (VALUES (1, 26, 'Republic Day'), (8, 15, 'Independence Day'), (10, 2, 'Gandhi Jayanti')) AS h(m, d, n),
     (VALUES (2026), (2027)) AS yy(y);

-- Quiet hours for patient messages (21:00-09:00 by default when switched on); a clinic setting read by the messaging module.
CREATE TABLE clinic_quiet_hours (
  tenant_id  uuid PRIMARY KEY REFERENCES tenants(id),
  enabled    boolean NOT NULL DEFAULT false,
  from_time  time NOT NULL DEFAULT '21:00',
  to_time    time NOT NULL DEFAULT '09:00',
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (from_time <> to_time)
);
SELECT enforce_tenant_rls('clinic_quiet_hours');
