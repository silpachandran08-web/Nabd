-- ACC-003: DB backstop for one check-in per appointment; an arrived appointment keeps its slot taken.
-- shortcut: plain CREATE INDEX (Flyway runs in a transaction, CONCURRENTLY is not allowed); upgrade if the tables get large.
SET LOCK_TIMEOUT = '5s';
CREATE UNIQUE INDEX IF NOT EXISTS uq_queue_entries_appointment ON queue_entries (appointment_id)
  WHERE appointment_id IS NOT NULL AND legacy_duplicate = false;
DROP INDEX IF EXISTS uq_appointments_doctor_slot;
CREATE UNIQUE INDEX uq_appointments_doctor_slot ON appointments (doctor_id, start_time)
  WHERE status IN ('scheduled','arrived');
