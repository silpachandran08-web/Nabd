SET lock_timeout = '5s';
-- ACC-006: alternatives offered to a patient whose confirmed booking staff moved or blocked. One row per offer; a newer offer for the
-- same appointment supersedes the older 'sent' one. needs_reschedule_reason marks a booking a leave block covers ('doctor_leave').
-- Ids and times only: no patient name, no free text.
ALTER TABLE appointments ADD COLUMN needs_reschedule_reason text NULL CHECK (needs_reschedule_reason IS NULL OR needs_reschedule_reason ~ '^[a-z0-9_]{3,40}$');

CREATE TABLE appointment_change_offers (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  appointment_id     uuid NOT NULL REFERENCES appointments(id),
  reason_code        text NOT NULL CHECK (reason_code ~ '^[a-z0-9_]{3,40}$'),
  slots              timestamptz[] NOT NULL CHECK (cardinality(slots) BETWEEN 1 AND 3),
  status             text NOT NULL DEFAULT 'sent' CHECK (status IN ('sent','accepted','expired','superseded')),
  sent_at            timestamptz NOT NULL DEFAULT now(),
  expires_at         timestamptz NOT NULL,
  accepted_slot      timestamptz,
  new_appointment_id uuid REFERENCES appointments(id),
  created_by         uuid
);
CREATE INDEX idx_change_offers_appointment ON appointment_change_offers (tenant_id, appointment_id);

SELECT enforce_tenant_rls('appointment_change_offers');
