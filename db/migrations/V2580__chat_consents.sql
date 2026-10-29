SET lock_timeout = '5s';
-- IDC-005: consent decisions a first-time WhatsApp booker taps BEFORE any patient row exists (PAT-01: decision stored before
-- demographics). Keyed by the SHA-256 of the phone digits (same hash the Edge uses for households); copied into consents and
-- marked attached when the chat registration creates the patient. wamid is the evidence; (tenant, wamid, purpose) makes a replay a no-op.
CREATE TABLE chat_consents (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  phone_hash          text NOT NULL CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
  purpose             text NOT NULL CHECK (purpose IN ('data_processing', 'messaging', 'marketing')),
  granted             boolean NOT NULL,
  notice_version      text NOT NULL,
  language            text NOT NULL,
  wamid               text NOT NULL CHECK (length(wamid) BETWEEN 1 AND 128),
  decided_at          timestamptz NOT NULL DEFAULT now(),
  attached_patient_id uuid REFERENCES patients(id),
  attached_at         timestamptz,
  CONSTRAINT chat_consents_notice_fk FOREIGN KEY (tenant_id, notice_version, language) REFERENCES privacy_notices (tenant_id, version, language),
  CONSTRAINT chat_consents_attached_pair CHECK ((attached_patient_id IS NULL) = (attached_at IS NULL)),
  UNIQUE (tenant_id, wamid, purpose)
);
CREATE INDEX idx_chat_consents_phone ON chat_consents (tenant_id, phone_hash) WHERE attached_patient_id IS NULL;
SELECT enforce_tenant_rls('chat_consents');
