-- OPS-009: the three search branches. phone and MRN prefix ranges (text_pattern_ops), name substring (trigram).
SET lock_timeout = '5s';

-- migration-lint: allow MIG-001 CONCURRENTLY cannot run in the Flyway transaction (hangs the migrate); run during the deploy window
CREATE INDEX IF NOT EXISTS idx_patients_phone_last10 ON patients (tenant_id, phone_last10 text_pattern_ops);
-- migration-lint: allow MIG-001 same reason as above
CREATE INDEX IF NOT EXISTS idx_patients_mrn_prefix ON patients (tenant_id, mrn text_pattern_ops);
-- migration-lint: allow MIG-001 same reason as above
CREATE INDEX IF NOT EXISTS idx_patients_name_search_trgm ON patients USING gin (tenant_id, name_search gin_trgm_ops);
