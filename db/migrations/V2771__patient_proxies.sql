SET lock_timeout = '5s';
-- IDC-009: an adult member consents to a family member (the proxy) acting for them. Until the member accepts through the OTP link
-- the proxy may book, reschedule and check in but sees no records. Minors keep patients.guardian_id; this table is for adults.
CREATE TABLE patient_proxies (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  proxy_patient_id   uuid NOT NULL,
  subject_patient_id uuid NOT NULL,
  relation           text NOT NULL CHECK (relation ~ '^[a-z_]{2,20}$'),
  scope              text CHECK (scope IN ('BOOK_ONLY', 'BOOK_AND_RECORDS', 'FULL')),
  status             text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'revoked')),
  invited_at         timestamptz NOT NULL DEFAULT now(),
  accepted_at        timestamptz,
  revoked_at         timestamptz,
  revoked_by         text,
  evidence           jsonb,
  notice_version     text,
  CONSTRAINT patient_proxies_proxy_fk FOREIGN KEY (tenant_id, proxy_patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT patient_proxies_subject_fk FOREIGN KEY (tenant_id, subject_patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT patient_proxies_not_self CHECK (proxy_patient_id <> subject_patient_id),
  CONSTRAINT patient_proxies_active_scope CHECK (status <> 'active' OR (scope IS NOT NULL AND accepted_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_patient_proxies_live ON patient_proxies (tenant_id, proxy_patient_id, subject_patient_id) WHERE status <> 'revoked';
CREATE INDEX idx_patient_proxies_subject ON patient_proxies (tenant_id, subject_patient_id);
SELECT enforce_tenant_rls('patient_proxies');

-- token_hash is the SHA-256 of the link token (the token itself is only in the WhatsApp link). The OTP hash and attempt counter live
-- here too: one invite, one challenge at a time.
CREATE TABLE proxy_invites (
  token_hash       text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  proxy_id         uuid NOT NULL REFERENCES patient_proxies(id),
  expires_at       timestamptz NOT NULL,
  used_at          timestamptz,
  otp_hash         text,
  otp_expires_at   timestamptz,
  otp_attempts     integer NOT NULL DEFAULT 0,
  otp_challenge_id uuid
);
CREATE INDEX idx_proxy_invites_proxy ON proxy_invites (tenant_id, proxy_id);
SELECT enforce_tenant_rls('proxy_invites');

-- The link is opened by a person with no session: the only thing known is the token. This returns the invite's clinic (nothing else)
-- so the caller can bind it before reading the row under RLS.
CREATE FUNCTION proxy_invite_tenant(p_hash text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT tenant_id FROM proxy_invites WHERE token_hash = p_hash $$;
REVOKE ALL ON FUNCTION proxy_invite_tenant(text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION proxy_invite_tenant(text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
