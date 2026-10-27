SET lock_timeout = '5s';
-- IDC-015: OTP-protected links to a document. token_hash = SHA-256 of a random 256-bit token (the token itself is never stored).
-- The OTP lives on the link row as a salted hash with its expiry and a failed-attempt counter; 5 wrong codes set locked_until.
CREATE TABLE secure_links (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  token_hash           text NOT NULL UNIQUE,
  document_type        text NOT NULL CHECK (document_type IN ('rx', 'invoice', 'report', 'document')),
  document_id          uuid NOT NULL,
  patient_id           uuid NOT NULL REFERENCES patients(id),
  recipient_patient_id uuid NOT NULL REFERENCES patients(id),
  expires_at           timestamptz NOT NULL,
  max_opens            int NOT NULL DEFAULT 20 CHECK (max_opens > 0),
  open_count           int NOT NULL DEFAULT 0,
  failed_otps          int NOT NULL DEFAULT 0,
  locked_until         timestamptz,
  otp_hash             text,
  otp_expires_at       timestamptz,
  created_by           uuid NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_secure_links_recipient ON secure_links (tenant_id, recipient_patient_id, created_at DESC);
SELECT enforce_tenant_rls('secure_links');

-- One row per attempt. outcome otp_sent counts OTP sends for the per-link and per-recipient limits (MF-24).
-- shortcut: only the DEFAULT partition exists; monthly partitions are added by a later ensure-partitions function (see phi_read_log_ensure_partitions).
CREATE TABLE secure_link_opens (
  id         bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id  uuid NOT NULL,
  link_id    uuid NOT NULL,
  opened_at  timestamptz NOT NULL DEFAULT now(),
  ip         inet,
  user_agent text,
  outcome    text NOT NULL CHECK (outcome IN ('opened', 'otp_failed', 'expired', 'locked', 'otp_sent')),
  PRIMARY KEY (tenant_id, opened_at, id)
) PARTITION BY RANGE (opened_at);
CREATE INDEX idx_secure_link_opens_link ON secure_link_opens (tenant_id, link_id, opened_at DESC);
CREATE TABLE secure_link_opens_default PARTITION OF secure_link_opens DEFAULT;
SELECT enforce_tenant_rls('secure_link_opens');
SELECT enforce_tenant_rls('secure_link_opens_default');

-- Per-clinic link lifetime in days (1 to 30). No admin endpoint yet: set in the database.
ALTER TABLE tenants ADD COLUMN secure_link_ttl_days int NOT NULL DEFAULT 7 CHECK (secure_link_ttl_days BETWEEN 1 AND 30);
