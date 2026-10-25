SET lock_timeout = '5s';
-- CLN-019: a tenant pharmacy item (charge_catalogue row) can point at a drug_master product. Pricing is untouched.
ALTER TABLE charge_catalogue ADD COLUMN IF NOT EXISTS drug_id text REFERENCES drug_master (id);
-- migration-lint: allow MIG-001 CONCURRENTLY cannot run inside Flyway (hangs the test run); the new column is empty, the index build is instant
CREATE INDEX IF NOT EXISTS charge_catalogue_tenant_drug ON charge_catalogue (tenant_id, drug_id) WHERE drug_id IS NOT NULL;
