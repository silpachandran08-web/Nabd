SET lock_timeout = '5s';
-- ACC-029: household lookup finds the children of a guardian by guardian_id (phone matches use idx_patients_phone).
-- migration-lint: allow MIG-001 patients is small per tenant and the index is a partial one on guardian_id; plain CREATE INDEX because CONCURRENTLY hangs Flyway
CREATE INDEX IF NOT EXISTS idx_patients_guardian ON patients (tenant_id, guardian_id) WHERE guardian_id IS NOT NULL;
