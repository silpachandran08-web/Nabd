-- ACC-028 (MF-69 / QA-20): phones are validated per clinic country (libphonenumber) and stored in E.164.
-- Spec named V323; renumbered to this engineer's Sprint 12 range.

SET lock_timeout = '5s';

-- 1. A stored phone that failed validation keeps its raw value and is flagged for the desk to fix, never deleted.
--    The re-normalisation itself needs libphonenumber, so it runs in Java: PhoneRenormaliseJob
--    (app.maintenance.phone-renormalise.enabled=true, re-runnable). Saving a valid phone clears the flag.
ALTER TABLE patients ADD COLUMN IF NOT EXISTS phone_invalid boolean NOT NULL DEFAULT false;

-- GET /v1/patients/data-quality?issue=phone pages these by id; tiny partial index (only flagged rows).
-- migration-lint: allow MIG-001 partial index on the few flagged rows; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX IF NOT EXISTS idx_patients_phone_invalid ON patients (tenant_id, id) WHERE phone_invalid;

-- 2. Clinic country for phone defaults: UAE (AE) and Qatar (QA) clinics are allowed at the data layer, next to IN and KSA.
--    Expand only; provisioning still offers IN/KSA (provisioning_jobs keeps its own check) and the region stays immutable (V5).
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_region_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_region_check CHECK (region IN ('IN', 'KSA', 'AE', 'QA')) NOT VALID;
ALTER TABLE tenants VALIDATE CONSTRAINT tenants_region_check;
