SET lock_timeout = '5s';
-- ADM-041: time KPIs on the OPS-010 daily rollups, so the owner overview reads rollups only (never queue_entries or invoices).
-- Durations are seconds (suffix _s); a median is NULL when nothing was measured that day. Each *_n is the weight used to
-- combine days of a range. Ids, counts and times only: no PHI. New nullable/defaulted columns: no table rewrite.
ALTER TABLE tenant_daily_metrics
  ADD COLUMN bookings              int NOT NULL DEFAULT 0,   -- non-cancelled appointments due that day (no-show rate = no_shows / bookings)
  ADD COLUMN flow_n                int NOT NULL DEFAULT 0,   -- visits that reached the doctor
  ADD COLUMN wait_p50_s            int,                      -- ready for the doctor (after vitals, else check-in) -> consult start
  ADD COLUMN door_to_doctor_p50_s  int,                      -- check-in -> consult start
  ADD COLUMN consult_n             int NOT NULL DEFAULT 0,
  ADD COLUMN consult_p50_s         int,
  ADD COLUMN booking_response_n    int NOT NULL DEFAULT 0,   -- booking requests from a patient channel decided that day
  ADD COLUMN booking_response_p50_s int,                     -- request -> decision
  ADD COLUMN eta_n                 int NOT NULL DEFAULT 0,   -- copied from eta_accuracy_daily (ACC-019)
  ADD COLUMN eta_within_range_pct  numeric(5,4) CHECK (eta_within_range_pct BETWEEN 0 AND 1),
  ADD COLUMN sessions              int NOT NULL DEFAULT 0,   -- doctors with working hours that day who called a patient
  ADD COLUMN punctual_sessions_pct numeric(5,4) CHECK (punctual_sessions_pct BETWEEN 0 AND 1),
  ADD COLUMN checkin_to_vitals_p50_s int,                    -- check-in -> vitals done (visits with vitals only)
  ADD COLUMN checkout_p50_s        int,                      -- consult end -> visit completed
  ADD COLUMN wait_by_hour          jsonb NOT NULL DEFAULT '{}',  -- {local hour of check-in: median wait seconds}
  ADD COLUMN flow_refreshed_at     timestamptz;              -- last recompute of the columns above

-- seconds from the doctor's first scheduled start that day to the first patient called (negative = early); NULL = no working hours or nobody called
ALTER TABLE doctor_daily_metrics ADD COLUMN first_call_delay_s int;
