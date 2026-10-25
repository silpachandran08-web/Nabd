SET lock_timeout = '5s';
-- ACC-019: ETA predictions as shown to patients (check-in, leave-home) and the daily accuracy rollup per doctor.
-- Ids and times only, no PHI. eta_prediction_log is RANGE-partitioned monthly on predicted_at like phi_read_log (V1390):
-- eta_prediction_log_ensure_partitions() creates the months ahead (the rollup job calls it), a DEFAULT partition never loses a row.
CREATE TABLE eta_prediction_log (
  id               bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id        uuid NOT NULL,
  queue_entry_id   uuid,
  appointment_id   uuid,
  doctor_id        uuid NOT NULL,
  clinic_date      date NOT NULL,
  predicted_at     timestamptz NOT NULL,
  p50_at           timestamptz NOT NULL,
  p80_at           timestamptz NOT NULL CHECK (p80_at >= p50_at),
  horizon_min      int NOT NULL CHECK (horizon_min >= 0),
  actual_called_at timestamptz,
  PRIMARY KEY (tenant_id, predicted_at, id)
) PARTITION BY RANGE (predicted_at);
CREATE INDEX idx_eta_prediction_log_entry ON eta_prediction_log (tenant_id, queue_entry_id, predicted_at DESC);
CREATE INDEX idx_eta_prediction_log_day ON eta_prediction_log (tenant_id, clinic_date, doctor_id);
CREATE TABLE eta_prediction_log_default PARTITION OF eta_prediction_log DEFAULT;

CREATE TABLE eta_accuracy_daily (
  tenant_id    uuid NOT NULL,
  doctor_id    uuid NOT NULL,
  clinic_date  date NOT NULL,
  n            int NOT NULL CHECK (n >= 0),
  mae_s        int NOT NULL,
  p80_hit_rate numeric(5,4) NOT NULL CHECK (p80_hit_rate BETWEEN 0 AND 1),
  within10_pct numeric(5,4) NOT NULL CHECK (within10_pct BETWEEN 0 AND 1),
  PRIMARY KEY (tenant_id, doctor_id, clinic_date)
);

SELECT enforce_tenant_rls('eta_prediction_log');
SELECT enforce_tenant_rls('eta_prediction_log_default');
SELECT enforce_tenant_rls('eta_accuracy_daily');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON eta_prediction_log_default FROM nabd_app;  -- rows are reached through the parent
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    ALTER TABLE eta_prediction_log OWNER TO nabd_definer;  -- creating a partition requires owning the parent
  END IF;
END $$;

CREATE FUNCTION eta_prediction_log_ensure_partitions(p_from date, p_months_ahead int) RETURNS int
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  m date := date_trunc('month', p_from)::date;
  stop date := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => GREATEST(p_months_ahead, 0)))::date;
  part text;
  n int := 0;
BEGIN
  WHILE m <= stop LOOP
    part := format('eta_prediction_log_y%sm%s', to_char(m, 'YYYY'), to_char(m, 'MM'));
    IF to_regclass(part) IS NULL THEN
      EXECUTE format('CREATE TABLE %I PARTITION OF eta_prediction_log FOR VALUES FROM (%L) TO (%L)', part,
                     m::text || ' 00:00:00+00', (m + interval '1 month')::date::text || ' 00:00:00+00');
      PERFORM enforce_tenant_rls(part::regclass);
      IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
        EXECUTE format('REVOKE ALL ON %I FROM nabd_app', part);
      END IF;
      n := n + 1;
    END IF;
    m := (m + interval '1 month')::date;
  END LOOP;
  RETURN n;
END $$;

REVOKE ALL ON FUNCTION eta_prediction_log_ensure_partitions(date, int) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION eta_prediction_log_ensure_partitions(date, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();

SELECT eta_prediction_log_ensure_partitions(((now() AT TIME ZONE 'UTC') - interval '1 month')::date, 3);
