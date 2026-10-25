SET lock_timeout = '5s';
-- OPS-010: daily rollups for the owner dashboards. One row per tenant (and doctor) per clinic day, upserted by the
-- reporting-rollup event consumer; a closed day (day_closes) is not touched by late events. Idempotent by construction:
-- every upsert recomputes the whole day from source, so a replay writes the same numbers.
CREATE TABLE tenant_daily_metrics (
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  day            date NOT NULL,
  visits         int NOT NULL DEFAULT 0,
  completed      int NOT NULL DEFAULT 0,
  no_shows       int NOT NULL DEFAULT 0,
  billed         numeric(14,3) NOT NULL DEFAULT 0,
  collected      numeric(14,3) NOT NULL DEFAULT 0,
  tax            numeric(14,3) NOT NULL DEFAULT 0,
  currency       char(3),
  by_method      jsonb NOT NULL DEFAULT '{}',
  packages_sold  int NOT NULL DEFAULT 0,
  last_event_id  text,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, day)
);
SELECT enforce_tenant_rls('tenant_daily_metrics');

CREATE TABLE doctor_daily_metrics (
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  doctor_id        uuid NOT NULL REFERENCES staff(id),
  day              date NOT NULL,
  visits           int NOT NULL DEFAULT 0,
  completed        int NOT NULL DEFAULT 0,
  median_consult_s int,  -- stays null until queue stage events carry consult durations (T-105)
  no_shows         int NOT NULL DEFAULT 0,
  billed           numeric(14,3) NOT NULL DEFAULT 0,
  collected        numeric(14,3) NOT NULL DEFAULT 0,
  last_event_id    text,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, doctor_id, day)
);
SELECT enforce_tenant_rls('doctor_daily_metrics');
