SET lock_timeout = '5s';
-- ACC-025: no-show marking. no_show_marked_by NULL = the system (auto-mark job). decision_source_before_no_show keeps the
-- booking's own decision_source so undo can restore it (auto-mark sets decision_source = 'system').
ALTER TABLE appointments
  ADD COLUMN IF NOT EXISTS no_show_at timestamptz,
  ADD COLUMN IF NOT EXISTS no_show_marked_by uuid,
  ADD COLUMN IF NOT EXISTS no_show_reason_code text,
  ADD COLUMN IF NOT EXISTS decision_source_before_no_show text,
  ADD COLUMN IF NOT EXISTS late_arrival_queue_entry_id uuid;

-- migration-lint: allow MIG-001 partial index over status 'scheduled' only; CONCURRENTLY hangs the Flyway transaction
CREATE INDEX IF NOT EXISTS idx_appointments_due_no_show ON appointments (tenant_id, start_time) WHERE status = 'scheduled';
