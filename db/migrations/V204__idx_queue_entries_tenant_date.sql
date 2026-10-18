-- OPS-001 (DP-01): tenant-wide queue-by-day reads (day board, reports) that have no doctor_id.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_queue_entries_tenant_date ON queue_entries (tenant_id, queue_date);
