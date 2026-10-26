SET lock_timeout = '5s';
-- ACC-016: learned consult-time distribution per (doctor, visit type). Ids, counts and seconds only: no PHI.
-- Median/p80 are nightly robust values (30-day window); ewma_* are the online (every consult) log-duration mean and variance.
CREATE TABLE service_time_stats (
  tenant_id    uuid NOT NULL,
  doctor_id    uuid NOT NULL,
  visit_type   text NOT NULL CHECK (visit_type IN ('new','follow_up','procedure','review')),
  hour_bucket  smallint CHECK (hour_bucket IS NULL OR hour_bucket BETWEEN 0 AND 23),
  n            int NOT NULL DEFAULT 0,
  mu_log       double precision,
  sigma_log    double precision,
  ewma_mu      double precision,
  ewma_var     double precision,
  median_s     int,
  p80_s        int,
  prior_source text NOT NULL DEFAULT 'slot' CHECK (prior_source IN ('slot','clinic','specialty','own')),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_service_time_stats ON service_time_stats (tenant_id, doctor_id, visit_type, (coalesce(hour_bucket, -1)));

-- platform reference data (no tenant_id, read-only to nabd_app, changes ship as migrations). 'default' is the generic row used
-- when a department has no specialty code; clinics' own data replaces it as consults are learned.
CREATE TABLE specialty_service_defaults (
  specialty_code text NOT NULL,
  visit_type     text NOT NULL CHECK (visit_type IN ('new','follow_up','procedure','review')),
  median_s       int NOT NULL CHECK (median_s > 0),
  p80_s          int NOT NULL CHECK (p80_s >= median_s),
  PRIMARY KEY (specialty_code, visit_type)
);
INSERT INTO specialty_service_defaults (specialty_code, visit_type, median_s, p80_s) VALUES
  ('default','new',900,1200),('default','follow_up',600,780),('default','procedure',1800,2400),('default','review',600,780);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON specialty_service_defaults FROM nabd_app;
    GRANT SELECT ON specialty_service_defaults TO nabd_app;
  END IF;
END $$;

-- a consult longer than 4x the doctor's median is capped before it is learned; the event row says so
ALTER TABLE queue_entry_events ADD COLUMN capped boolean NOT NULL DEFAULT false;

SELECT enforce_tenant_rls('service_time_stats');
