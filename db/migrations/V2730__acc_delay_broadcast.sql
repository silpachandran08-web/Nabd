SET lock_timeout = '5s';
-- ACC-024: a delay lapses at its session end and is broadcast; every send (or refusal to send) is a recipient row.
ALTER TABLE doctor_delays ADD COLUMN expires_at timestamptz;
ALTER TABLE doctor_delays ADD COLUMN broadcast boolean NOT NULL DEFAULT true;
ALTER TABLE doctor_delays ADD COLUMN reason_code text;
-- history rows: the end of their clinic day (the lapse rule of ACC-001)
UPDATE doctor_delays dd SET expires_at = ((dd.clinic_date + 1)::timestamp AT TIME ZONE COALESCE(t.timezone, 'UTC'))
  FROM tenants t WHERE t.id = dd.tenant_id AND dd.expires_at IS NULL;
ALTER TABLE doctor_delays ADD CONSTRAINT chk_doctor_delays_expires CHECK (expires_at IS NOT NULL) NOT VALID;
ALTER TABLE doctor_delays VALIDATE CONSTRAINT chk_doctor_delays_expires;
ALTER TABLE doctor_delays ALTER COLUMN expires_at SET NOT NULL;

-- One row per (delay or pause, patient). No names, no phone numbers, no message text: ids, statuses and times only.
CREATE TABLE delay_broadcast_recipients (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  doctor_id      uuid NOT NULL,
  delay_id       uuid REFERENCES doctor_delays(id),
  pause_id       uuid REFERENCES doctor_session_pauses(id),
  appointment_id uuid,
  queue_entry_id uuid,
  patient_id     uuid NOT NULL,
  message_ref    text,
  expected_at    timestamptz,
  status         text NOT NULL CHECK (status IN ('queued','sent','held','skipped_no_consent','delivered','failed')),
  response       text CHECK (response IN ('ok','reschedule','cancel')),
  responded_at   timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (delay_id IS NOT NULL OR pause_id IS NOT NULL)
);
CREATE INDEX idx_delay_recipients_delay ON delay_broadcast_recipients (tenant_id, delay_id);
CREATE INDEX idx_delay_recipients_pause ON delay_broadcast_recipients (tenant_id, pause_id);
CREATE INDEX idx_delay_recipients_patient ON delay_broadcast_recipients (tenant_id, patient_id, created_at DESC);
SELECT enforce_tenant_rls('delay_broadcast_recipients');

-- Neutral pause notice: no reason, no name. English only here; translation review and Meta approval are Needs external action.
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('queue_paused','en','UTILITY','The doctor is briefly unavailable; we will update you.',ARRAY[]::text[],ARRAY[]::text[],'Tells waiting patients the queue is paused, without a reason');
