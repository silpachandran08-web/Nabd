-- OPS-061 expand step (DEC-25): tenants.region_code (FK to regions) becomes the one region value every code path reads.
-- The old text column `region` stays for one release as a mirror of region_code, kept by trigger (older writers and
-- test fixtures still set it); the contract step that drops it ships later. KSA tenants are test-only: KSA -> SA.
-- tenants.timezone becomes NOT NULL and defaults from the region.
SET lock_timeout = '5s';

-- 1. The old CHECK learns SA, so the mirror can hold the new value.
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_region_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_region_check CHECK (region IN ('IN', 'KSA', 'SA', 'AE', 'QA')) NOT VALID;
ALTER TABLE tenants VALIDATE CONSTRAINT tenants_region_check;

ALTER TABLE tenants ADD COLUMN region_code char(2) REFERENCES regions(code);

-- 2. One immutability rule on region_code (the legacy KSA -> SA normalisation of the mirror is the only region change allowed).
CREATE OR REPLACE FUNCTION prevent_region_change() RETURNS trigger AS $$
BEGIN
  IF OLD.region_code IS NOT NULL AND NEW.region_code IS DISTINCT FROM OLD.region_code THEN
    RAISE EXCEPTION 'REGION_IMMUTABLE: tenants.region_code cannot change once the tenant exists (NB-002, OPS-061)' USING ERRCODE = 'NR001';
  END IF;
  IF NEW.region IS DISTINCT FROM OLD.region AND NOT (OLD.region = 'KSA' AND NEW.region = 'SA') THEN
    RAISE EXCEPTION 'REGION_IMMUTABLE: tenants.region cannot change once the tenant exists (NB-002, OPS-061)' USING ERRCODE = 'NR001';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. Backfill (the immutability trigger lets region_code go from NULL to a value, and KSA -> SA on the mirror).
UPDATE tenants SET region_code = CASE region WHEN 'KSA' THEN 'SA' ELSE region END WHERE region_code IS NULL;
UPDATE tenants SET region = 'SA' WHERE region = 'KSA';

-- 4. New rows: region_code from the old column if only that was given, the mirror follows, timezone defaults from the region.
CREATE FUNCTION tenants_region_defaults() RETURNS trigger AS $$
BEGIN
  NEW.region_code := COALESCE(NEW.region_code, CASE NEW.region WHEN 'KSA' THEN 'SA' ELSE NEW.region END);
  NEW.region := NEW.region_code;
  NEW.timezone := COALESCE(NEW.timezone, (SELECT r.default_timezone FROM regions r WHERE r.code = NEW.region_code));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_region_defaults BEFORE INSERT ON tenants
  FOR EACH ROW EXECUTE FUNCTION tenants_region_defaults();

-- 5. timezone: no more 'UTC' column default (the region decides); existing NULLs get their region's zone; then NOT NULL.
ALTER TABLE tenants ALTER COLUMN timezone DROP DEFAULT;
UPDATE tenants t SET timezone = r.default_timezone FROM regions r WHERE r.code = t.region_code AND t.timezone IS NULL;
ALTER TABLE tenants ADD CONSTRAINT tenants_timezone_nn CHECK (timezone IS NOT NULL) NOT VALID;
ALTER TABLE tenants VALIDATE CONSTRAINT tenants_timezone_nn;
ALTER TABLE tenants ALTER COLUMN timezone SET NOT NULL;

-- 6. Provisioning requests and licence rows accept the four regions (KSA rows become SA; a licence's region is data, not logic).
ALTER TABLE master.provisioning_jobs DROP CONSTRAINT IF EXISTS provisioning_jobs_region_check;
ALTER TABLE master.provisioning_jobs ADD CONSTRAINT provisioning_jobs_region_check CHECK (region IN ('IN', 'KSA', 'SA', 'AE', 'QA')) NOT VALID;
ALTER TABLE master.provisioning_jobs VALIDATE CONSTRAINT provisioning_jobs_region_check;
UPDATE master.provisioning_jobs SET region = 'SA' WHERE region = 'KSA';
ALTER TABLE licence_registry DROP CONSTRAINT IF EXISTS licence_registry_region_check;
ALTER TABLE licence_registry ADD CONSTRAINT licence_registry_region_check CHECK (region IN ('IN', 'KSA', 'SA', 'AE', 'QA')) NOT VALID;
ALTER TABLE licence_registry VALIDATE CONSTRAINT licence_registry_region_check;
