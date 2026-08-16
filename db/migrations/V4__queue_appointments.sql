-- Queue & Appointments core (NB-067 scheduling, NB-068 walk-in tokens,
-- NB-069 queue state machine, NB-072 cancel/reschedule, minimal NB-074
-- doctor availability). Deferred: real-time sync (NB-071, needs a
-- WebSocket/SSE layer), self-booking (NB-075, needs integrations),
-- wait-time estimation (NB-076, needs historical data to estimate from).

-- ── doctor availability: minimal weekly template + leave blocks ──
CREATE TABLE doctor_working_hours (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  doctor_id    uuid NOT NULL REFERENCES staff(id),
  day_of_week  int NOT NULL CHECK (day_of_week BETWEEN 0 AND 6), -- 0=Sunday..6=Saturday
  start_time   time NOT NULL,
  end_time     time NOT NULL,
  slot_minutes int NOT NULL DEFAULT 15 CHECK (slot_minutes > 0),
  CHECK (end_time > start_time),
  UNIQUE (doctor_id, day_of_week, start_time)
);
ALTER TABLE doctor_working_hours ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON doctor_working_hours
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ponytail: leave blocks the whole day rather than modelling partial-day
-- leave, and doesn't flag/cascade to existing bookings in range (NB-074's
-- "existing bookings flagged for reschedule" AC) — add a reconciliation
-- step once there's a notification channel (NB-125/127) to flag through.
CREATE TABLE doctor_leave (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  doctor_id  uuid NOT NULL REFERENCES staff(id),
  date_from  date NOT NULL,
  date_to    date NOT NULL,
  reason     text,
  CHECK (date_to >= date_from)
);
ALTER TABLE doctor_leave ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON doctor_leave
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_doctor_leave_range ON doctor_leave (doctor_id, date_from, date_to);

-- ── appointments: pre-booked slots ──
CREATE TABLE appointments (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  patient_id    uuid NOT NULL REFERENCES patients(id),
  doctor_id     uuid NOT NULL REFERENCES staff(id),
  start_time    timestamptz NOT NULL,
  end_time      timestamptz NOT NULL,
  status        text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','cancelled','completed','no_show')),
  cancel_reason text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CHECK (end_time > start_time),
  CHECK ((status = 'cancelled') = (cancel_reason IS NOT NULL))
);
CREATE TRIGGER trg_appointments_updated_at BEFORE UPDATE ON appointments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE appointments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON appointments
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- The actual double-booking guard (NB-067's "no double-booking under
-- concurrent load" AC): a partial unique index enforced by Postgres itself,
-- not an application check-then-insert, which always has a race window
-- between the check and the write. Partial (not a plain UNIQUE constraint)
-- so a cancelled slot immediately frees the time for a new booking.
CREATE UNIQUE INDEX uq_appointments_doctor_slot ON appointments (doctor_id, start_time) WHERE status = 'scheduled';

CREATE INDEX idx_appointments_doctor_date ON appointments (doctor_id, start_time);
CREATE INDEX idx_appointments_patient ON appointments (patient_id);
CREATE INDEX idx_appointments_tenant_created ON appointments (tenant_id, created_at, id);

-- ── queue: today's walk-through. appointment_id is null for walk-ins. ──
-- "not_arrived" isn't a stored state: a scheduled appointment with no
-- queue_entries row for today simply hasn't checked in yet. Only the
-- check-in-and-later states are materialized.
CREATE TABLE queue_entries (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  appointment_id  uuid REFERENCES appointments(id),
  patient_id      uuid NOT NULL REFERENCES patients(id),
  doctor_id       uuid NOT NULL REFERENCES staff(id),
  queue_date      date NOT NULL,
  token_number    int NOT NULL,
  status          text NOT NULL DEFAULT 'checked_in' CHECK (status IN
                    ('checked_in','waiting','vitals','in_consult','checkout_pending','completed','no_show')),
  priority        boolean NOT NULL DEFAULT false,
  priority_reason text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (NOT priority OR priority_reason IS NOT NULL), -- reason required to SET priority; clearing it may keep the reason on record
  UNIQUE (tenant_id, doctor_id, queue_date, token_number)
);
CREATE TRIGGER trg_queue_entries_updated_at BEFORE UPDATE ON queue_entries
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE queue_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON queue_entries
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE INDEX idx_queue_doctor_date ON queue_entries (tenant_id, doctor_id, queue_date);
