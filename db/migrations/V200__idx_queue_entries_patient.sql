-- OPS-001 (DP-01): patient-scoped reads of queue_entries (findLastVisitAt, timeline) were a cross-tenant seq scan.
-- One CONCURRENTLY statement per file: Flyway runs it outside a transaction, no ACCESS EXCLUSIVE lock, writers never blocked.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_queue_entries_patient ON queue_entries (tenant_id, patient_id, queue_date DESC);
