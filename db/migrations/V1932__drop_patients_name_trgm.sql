-- OPS-009 cutover: the tenant-less trigram index on name (V3) is no longer used by any query
-- (search reads name_search through idx_patients_name_search_trgm).
SET lock_timeout = '5s';
DROP INDEX IF EXISTS idx_patients_name_trgm;
