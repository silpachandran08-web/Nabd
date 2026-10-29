SET lock_timeout = '5s';
-- ACC-009/ACC-010: booking requests. 'requested' holds its slot (the exclusion constraint covers it) until decided; 'declined' frees it.
ALTER TABLE appointments DROP CONSTRAINT IF EXISTS appointments_status_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_status_check
  CHECK (status IN ('requested','scheduled','arrived','cancelled','completed','no_show','declined')) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT appointments_status_check;

ALTER TABLE appointments DROP CONSTRAINT IF EXISTS ex_appointments_doctor_overlap;
-- migration-lint: allow MIG-001 the exclusion constraint is rebuilt with a wider status set; same rows, one short table lock
ALTER TABLE appointments ADD CONSTRAINT ex_appointments_doctor_overlap
  EXCLUDE USING gist (tenant_id WITH =, doctor_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
  WHERE (booking_kind = 'slot' AND status IN ('requested','scheduled','arrived'));

ALTER TABLE appointments
  ADD COLUMN IF NOT EXISTS channel text NOT NULL DEFAULT 'desk',
  ADD COLUMN IF NOT EXISTS visit_type text,
  ADD COLUMN IF NOT EXISTS decision_source text,
  ADD COLUMN IF NOT EXISTS decided_by uuid,
  ADD COLUMN IF NOT EXISTS decided_at timestamptz,
  ADD COLUMN IF NOT EXISTS policy_version int;
ALTER TABLE appointments ADD CONSTRAINT appointments_channel_check
  CHECK (channel IN ('desk','whatsapp','web','widget','app','call_centre','aggregator')) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT appointments_channel_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_decision_source_check
  CHECK (decision_source IS NULL OR decision_source IN ('auto','staff','system')) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT appointments_decision_source_check;

-- migration-lint: allow MIG-001 partial index on a status that no row has yet
CREATE INDEX idx_appointments_requested ON appointments (tenant_id, doctor_id, start_time) WHERE status = 'requested';

-- Per-request detail. patient_note is free text typed by the patient: never logged, never in an event.
CREATE TABLE booking_request_details (
  appointment_id      uuid PRIMARY KEY REFERENCES appointments(id),
  tenant_id           uuid NOT NULL,
  review_reason       text NOT NULL CHECK (review_reason IN ('new_patient','over_cap','triage_flag','procedure','group','policy_review')),
  triage_flags        text[] NOT NULL DEFAULT '{}',
  patient_note        text CHECK (char_length(patient_note) <= 280),
  sla_due_at          timestamptz NOT NULL,
  escalated_at        timestamptz,
  decline_reason_code text
);
CREATE INDEX idx_booking_request_details_sla ON booking_request_details (tenant_id, sla_due_at) WHERE escalated_at IS NULL;

-- Routing policy per clinic. A row is written by the booking-rules publish (ACC-008); without one the defaults in code apply.
CREATE TABLE booking_routing_settings (
  tenant_id          uuid PRIMARY KEY,
  acceptance_mode    text NOT NULL CHECK (acceptance_mode IN ('auto','hybrid','manual')),
  daily_auto_cap     int NOT NULL CHECK (daily_auto_cap BETWEEN 0 AND 1000),
  review_sla_minutes int NOT NULL CHECK (review_sla_minutes BETWEEN 1 AND 1440),
  policy_version     int NOT NULL DEFAULT 1,
  updated_at         timestamptz NOT NULL DEFAULT now()
);

-- Alternative slots offered to the patient (suggest); the patient's choice confirms the request.
CREATE TABLE booking_request_offers (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  appointment_id uuid NOT NULL REFERENCES appointments(id),
  slots          timestamptz[] NOT NULL CHECK (cardinality(slots) BETWEEN 1 AND 3),
  status         text NOT NULL DEFAULT 'open' CHECK (status IN ('open','chosen','none','expired')),
  chosen_slot    timestamptz,
  expires_at     timestamptz NOT NULL,
  created_by     uuid,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_request_offers_appt ON booking_request_offers (tenant_id, appointment_id);

SELECT enforce_tenant_rls('booking_request_details');
SELECT enforce_tenant_rls('booking_routing_settings');
SELECT enforce_tenant_rls('booking_request_offers');
