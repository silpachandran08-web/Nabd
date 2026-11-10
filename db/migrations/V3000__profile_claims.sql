SET lock_timeout = '5s';
-- IDC-012: the age-18 handover. A daily job invites a minor with a guardian on file 14 days before the 18th birthday; from the
-- birthday the young adult claims the profile with an OTP to their own number (or at the desk) and may keep the guardian as a
-- proxy (IDC-009 scopes). 30 days after the birthday with no claim, guardian access ends. token_hash is the SHA-256 of the link
-- token (the token is only in the WhatsApp link). The OTP hash and attempt counter live here, one challenge at a time, as in
-- proxy_invites (V2771).
CREATE TABLE profile_claims (
  token_hash          text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  patient_id          uuid NOT NULL,
  guardian_patient_id uuid NOT NULL,
  handover_date       date NOT NULL,
  sent_at             timestamptz NOT NULL DEFAULT now(),
  expires_at          timestamptz NOT NULL,
  claimed_at          timestamptz,
  outcome             text CHECK (outcome IN ('claimed', 'claimed_at_desk', 'expired', 'superseded')),
  kept_scope          text CHECK (kept_scope IN ('NONE', 'BOOK_ONLY', 'BOOK_AND_RECORDS')),
  otp_hash            text,
  otp_expires_at      timestamptz,
  otp_attempts        integer NOT NULL DEFAULT 0,
  otp_challenge_id    uuid,
  CONSTRAINT profile_claims_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT profile_claims_guardian_fk FOREIGN KEY (tenant_id, guardian_patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT profile_claims_claimed CHECK ((outcome IN ('claimed', 'claimed_at_desk')) = (claimed_at IS NOT NULL AND kept_scope IS NOT NULL))
);
-- one handover per patient and 18th birthday: the job's idempotency key
CREATE UNIQUE INDEX uq_profile_claims_patient_handover ON profile_claims (tenant_id, patient_id, handover_date);
CREATE INDEX idx_profile_claims_guardian ON profile_claims (tenant_id, guardian_patient_id);
CREATE INDEX idx_profile_claims_open ON profile_claims (tenant_id, expires_at) WHERE outcome IS NULL;
SELECT enforce_tenant_rls('profile_claims');

-- The claim link is opened with no session: this returns the claim's clinic (nothing else) so the caller can bind it before
-- reading the row under RLS (same as proxy_invite_tenant).
CREATE FUNCTION profile_claim_tenant(p_hash text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT tenant_id FROM profile_claims WHERE token_hash = p_hash $$;
REVOKE ALL ON FUNCTION profile_claim_tenant(text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION profile_claim_tenant(text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
