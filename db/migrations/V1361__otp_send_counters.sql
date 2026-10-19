-- PLT-006: per-number and per-tenant OTP send limits (SYS-01). Keyed by an HMAC of the normalised phone number, never
-- the number itself. One row per (tenant, number, 15-minute window); the 24 h and tenant-hour limits sum the windows.
-- Rows older than 48 h are purged by OtpCounterPurgeJob through purge_otp_send_counters().
CREATE TABLE otp_send_counters (
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  phone_hmac    bytea NOT NULL,
  window_start  timestamptz NOT NULL,
  sends         int NOT NULL DEFAULT 0,
  last_sent_at  timestamptz,
  PRIMARY KEY (tenant_id, phone_hmac, window_start)
);
CREATE INDEX idx_otp_send_counters_tenant_window ON otp_send_counters (tenant_id, window_start);
CREATE INDEX idx_otp_send_counters_window ON otp_send_counters (window_start);

-- Per-plan tenant hourly cap; NULL = app.auth.otp.tenant-hourly-cap (default 200).
ALTER TABLE master.plans ADD COLUMN otp_hourly_cap integer CHECK (otp_hourly_cap > 0);

-- Cross-tenant housekeeping: the app role is confined to one tenant by FORCE RLS.
CREATE FUNCTION purge_otp_send_counters() RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$ WITH d AS (DELETE FROM otp_send_counters WHERE window_start < now() - interval '48 hours' RETURNING 1) SELECT count(*)::int FROM d $$;
REVOKE ALL ON FUNCTION purge_otp_send_counters() FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION purge_otp_send_counters() TO nabd_app;
  END IF;
END $$;

SELECT enforce_tenant_rls('otp_send_counters');
SELECT nabd_adopt_definer_functions();
