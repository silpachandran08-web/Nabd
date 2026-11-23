SET lock_timeout = '5s';
-- ADM-042: the owner's live operations board owns only its alert thresholds. One row per clinic (facility = tenant); a clinic
-- without a row uses the product defaults (reports.dto.OpsThresholds.DEFAULTS). thresholds:
-- {pharmacyWaitMin, labTatBreach, requestWaitMin, doctorLateMin}. Numbers and a flag only: no PHI.
CREATE TABLE ops_alert_thresholds (
  tenant_id  uuid PRIMARY KEY REFERENCES tenants(id),
  thresholds jsonb NOT NULL CHECK (jsonb_typeof(thresholds) = 'object'),
  updated_at timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('ops_alert_thresholds');
