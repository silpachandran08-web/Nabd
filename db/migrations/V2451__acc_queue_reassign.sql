SET lock_timeout = '5s';
-- ACC-022: reassign keeps the old token ("T-14, was T-9") for the rest of the day; the board reads one doctor-day at a time.
ALTER TABLE queue_entries ADD COLUMN previous_token int;
ALTER TABLE queue_entries ADD COLUMN reassigned_from_doctor_id uuid;
-- migration-lint: allow MIG-001 queue_entries is small per tenant-day and the migrator runs before traffic; CONCURRENTLY hangs Flyway
CREATE INDEX idx_queue_entries_board ON queue_entries (tenant_id, queue_date, doctor_id, status) WHERE class = 'ambulatory';
