-- E10 Queue, Scheduling & Availability (NB-100): doctor delay ladder & clear-delay.
-- The patient-facing WhatsApp broadcast half of this ticket (and its "bypasses the prayer-window
-- hold" AC) needs E17's messaging infrastructure, which doesn't exist yet — deferred, same as
-- every other WhatsApp-dependent ticket this session. What ships here is the real, in-app half:
-- announcing/clearing a delay and keeping its history, which the queue/arrivals views can surface
-- today without a messaging channel.
CREATE TABLE doctor_delays (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  doctor_id     uuid NOT NULL REFERENCES staff(id),
  delay_minutes int NOT NULL CHECK (delay_minutes > 0),
  reason        text,
  announced_by  uuid NOT NULL REFERENCES staff(id),
  announced_at  timestamptz NOT NULL DEFAULT now(),
  cleared_by    uuid REFERENCES staff(id),
  cleared_at    timestamptz,
  CHECK ((cleared_at IS NULL) = (cleared_by IS NULL))
);
ALTER TABLE doctor_delays ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON doctor_delays
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE INDEX idx_doctor_delays_history ON doctor_delays (doctor_id, announced_at DESC);
-- at most one active (uncleared) delay per doctor — a new announcement must clear the old one first
CREATE UNIQUE INDEX uq_doctor_delays_active ON doctor_delays (doctor_id) WHERE cleared_at IS NULL;
