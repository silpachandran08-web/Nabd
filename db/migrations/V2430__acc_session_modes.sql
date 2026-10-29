SET lock_timeout = '5s';
-- ACC-011: each working-hours block (a "session") runs as booked slots, walk-in tokens, or both, with its own online cap.
-- A mode change applies to future dates only: effective_from is the first clinic date of session_mode,
-- earlier dates keep previous_session_mode (see queue.SessionModes).
ALTER TABLE doctor_working_hours
  ADD COLUMN session_mode text NOT NULL DEFAULT 'slot' CHECK (session_mode IN ('slot','token','hybrid')),
  ADD COLUMN previous_session_mode text NOT NULL DEFAULT 'slot' CHECK (previous_session_mode IN ('slot','token','hybrid')),
  ADD COLUMN online_cap int NULL CHECK (online_cap IS NULL OR online_cap >= 0),
  ADD COLUMN label text NULL CHECK (label IS NULL OR length(label) <= 60),
  ADD COLUMN effective_from date NULL;

ALTER TABLE appointments
  ADD COLUMN session_token int NULL CHECK (session_token IS NULL OR session_token >= 1),
  ADD COLUMN working_hours_id uuid NULL REFERENCES doctor_working_hours(id) ON DELETE SET NULL;

-- one reserved number per session and date (start_time of a token booking is the session window start)
-- migration-lint: allow MIG-001 new nullable columns, partial index on rows that do not exist yet
CREATE UNIQUE INDEX uq_appointments_session_token
  ON appointments (tenant_id, doctor_id, working_hours_id, session_token, start_time)
  WHERE booking_kind = 'token' AND status IN ('scheduled','arrived');
