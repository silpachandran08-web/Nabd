-- OPS-001 (DP-24): each duplicates a UNIQUE constraint's index (patients UNIQUE(tenant_id,mrn), staff UNIQUE(tenant_id,email),
-- attendance_records UNIQUE(tenant_id,staff_id,record_date), sessions UNIQUE(token_hash)) and only adds write cost.
-- All four are CONCURRENTLY so Flyway runs the file non-transactionally.
DROP INDEX CONCURRENTLY IF EXISTS idx_patients_mrn;
DROP INDEX CONCURRENTLY IF EXISTS idx_staff_tenant_email;
DROP INDEX CONCURRENTLY IF EXISTS idx_attendance_records_month;
DROP INDEX CONCURRENTLY IF EXISTS idx_sessions_token_hash;
