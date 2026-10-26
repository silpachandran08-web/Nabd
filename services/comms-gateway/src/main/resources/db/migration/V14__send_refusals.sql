SET lock_timeout = '5s';
-- IDC-006 / IDC-008: every send the gate refuses is recorded (reason only; no dob, no address, no message body).
-- patient_ref is the Core patient id (an opaque reference, same as outbound_messages).
CREATE TABLE send_refusals (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  patient_ref  uuid,
  message_id   uuid,                         -- the outbound_messages row when the send had been queued
  category     text NOT NULL CHECK (category IN ('UTILITY','MARKETING','AUTHENTICATION')),
  reason       text NOT NULL CHECK (reason IN ('no-marketing-consent','no-messaging-consent','minor-subject')),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_send_refusals_tenant_created ON send_refusals (tenant_id, created_at DESC);
ALTER TABLE send_refusals ENABLE ROW LEVEL SECURITY;
ALTER TABLE send_refusals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON send_refusals
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
