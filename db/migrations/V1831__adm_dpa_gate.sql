SET lock_timeout = '5s';
-- migration-lint: allow MIG-001 CONCURRENTLY hangs inside the Flyway transaction; partial index on a boolean that is false for every existing row
-- ADM-002: no real patient record until the clinic has a plan and a signed DPA. Expand-only.
-- DEPLOY NOTE (Needs external action, platform team): every EXISTING tenant starts 'demo_only'. A pilot that has a signed
-- PAPER DPA on file is opened by inserting one dpa_acceptances row (method 'paper', signer = its owner) and then
-- UPDATE tenants SET data_gate = 'live' WHERE id = ...; the guard trigger below refuses the update without that row and
-- without a master.subscriptions row. The list of such tenants comes from the platform team; none is guessed here.
ALTER TABLE tenants ADD COLUMN data_gate text NOT NULL DEFAULT 'demo_only' CHECK (data_gate IN ('demo_only', 'live'));

ALTER TABLE patients ADD COLUMN is_synthetic boolean NOT NULL DEFAULT false;
CREATE INDEX idx_patients_synthetic ON patients (tenant_id) WHERE is_synthetic;

CREATE TABLE dpa_acceptances (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  dpa_version     text NOT NULL,
  signer_owner_id uuid NOT NULL REFERENCES staff(id),
  method          text NOT NULL CHECK (method IN ('otp', 'otp_aadhaar_esign', 'otp_nafath', 'otp_uae_pass', 'paper')),
  signed_pdf_key  text,                       -- 'db:<id>' while PDFs live in signed_pdf; an object-store key once one exists
  signed_pdf      bytea,                      -- AES-GCM (FieldCipher, AAD tenant/table/column/row); NULL for 'paper'
  pdf_sha256      text,                       -- SHA-256 of the PDF bytes before encryption
  ip              inet,
  accepted_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, dpa_version)
);
SELECT enforce_tenant_rls('dpa_acceptances');

-- An acceptance is evidence: never edited or removed.
CREATE FUNCTION dpa_acceptances_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'dpa_acceptances rows are append-only' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_dpa_acceptances_append_only BEFORE UPDATE OR DELETE ON dpa_acceptances
  FOR EACH ROW EXECUTE FUNCTION dpa_acceptances_append_only();

-- Only a DPA acceptance (plus a subscription) can open the gate, whoever runs the UPDATE (support operator included).
-- SECURITY DEFINER so the check sees the tenant's rows whatever tenant the caller has bound.
CREATE FUNCTION tenants_data_gate_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, master AS $$
BEGIN
  IF NEW.data_gate = 'live' AND OLD.data_gate IS DISTINCT FROM 'live' THEN
    IF NOT EXISTS (SELECT 1 FROM dpa_acceptances WHERE tenant_id = NEW.id) THEN
      RAISE EXCEPTION 'data_gate can only be opened by a DPA acceptance' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM master.subscriptions WHERE tenant_id = NEW.id) THEN
      RAISE EXCEPTION 'data_gate needs a subscription' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_tenants_data_gate_guard BEFORE UPDATE OF data_gate ON tenants
  FOR EACH ROW EXECUTE FUNCTION tenants_data_gate_guard();
SELECT nabd_adopt_definer_functions();

-- Owner step-up OTP for the DPA (auth.api.StepUpOtp): purpose 'dpa', at most 5 wrong codes per challenge.
ALTER TABLE otp_requests DROP CONSTRAINT otp_requests_purpose_check;
ALTER TABLE otp_requests ADD CONSTRAINT otp_requests_purpose_check CHECK (purpose IN ('login', 'pin_reset', 'dpa')) NOT VALID;
ALTER TABLE otp_requests VALIDATE CONSTRAINT otp_requests_purpose_check;
ALTER TABLE otp_requests ADD COLUMN attempts smallint NOT NULL DEFAULT 0;
