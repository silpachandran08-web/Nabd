SET lock_timeout = '5s';
-- OPS-012: login OTPs and PIN-reset tokens are sent through the outbox (iam.otp.requested.v1) instead of calling
-- Meta inside AuthService's transaction. The event payload carries only ids; the code itself waits here,
-- AES-GCM encrypted with the app key (AesGcmCipher), read under RLS by the handler and cleared once sent.
-- staff.whatsapp_otp_hash / pin_reset_token_hash stay the verification source; this table is delivery only.
CREATE TABLE otp_requests (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  staff_id    uuid NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
  purpose     text NOT NULL CHECK (purpose IN ('login', 'pin_reset')),
  channel     text NOT NULL DEFAULT 'whatsapp' CHECK (channel IN ('whatsapp')),
  code_enc    bytea,                 -- NULL once sent or expired
  expires_at  timestamptz NOT NULL,
  sent_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_requests_staff ON otp_requests (tenant_id, staff_id, created_at DESC);
SELECT enforce_tenant_rls('otp_requests');
