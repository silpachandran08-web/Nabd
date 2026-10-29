SET lock_timeout = '5s';
-- IDC-018: ABDM Scan & Share. A visit's source and an identifier's verification channel gain 'abdm_scan_share'
-- (every earlier value is kept); abdm_share_requests makes the gateway callback idempotent per request id and holds the
-- outcome answered to ABDM (ids and a token number only, no profile data). abdm_tenant_for_hfr resolves the clinic of a
-- callback that carries no tenant: exactly one enabled, HFR-verified clinic may own the facility id, otherwise NULL.
ALTER TABLE queue_entries DROP CONSTRAINT queue_entries_source_check;
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_source_check CHECK (source IN
  ('walk_in', 'referral', 'online', 'social_media', 'returning', 'other', 'internal_transfer', 'abdm_scan_share')) NOT VALID;
ALTER TABLE queue_entries VALIDATE CONSTRAINT queue_entries_source_check;

ALTER TABLE patient_identifiers DROP CONSTRAINT patient_identifiers_verified_via_check;
ALTER TABLE patient_identifiers ADD CONSTRAINT patient_identifiers_verified_via_check CHECK (verified_via IN
  ('abdm_mobile_otp', 'abdm_aadhaar_otp', 'abdm_qr', 'abdm_enrol', 'abdm_scan_share')) NOT VALID;
ALTER TABLE patient_identifiers VALIDATE CONSTRAINT patient_identifiers_verified_via_check;

CREATE TABLE abdm_share_requests (
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  request_id    text NOT NULL CHECK (length(request_id) BETWEEN 1 AND 100),
  outcome       text NOT NULL CHECK (outcome IN ('token_issued', 'see_desk')),
  reason        text CHECK (reason IN ('closed', 'full', 'no_doctor', 'incomplete_profile', 'unavailable')),
  patient_id    uuid REFERENCES patients(id),
  queue_entry_id uuid,
  token_number  int,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, request_id)
);
SELECT enforce_tenant_rls('abdm_share_requests');

CREATE FUNCTION abdm_tenant_for_hfr(p_hip text) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT CASE WHEN count(*) = 1 THEN (array_agg(tenant_id))[1] END
    FROM tenant_integrations_abdm WHERE enabled AND verified_at IS NOT NULL AND (hfr_id = p_hip OR hip_id = p_hip)
$$;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION abdm_tenant_for_hfr(text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
