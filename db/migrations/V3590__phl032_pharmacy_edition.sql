SET lock_timeout = '5s';
-- PHL-032: pharmacy is sold as an edition (facility type + entitlements on the same Core), not as a separate service.

-- 1. A tenant may be a pharmacy or a lab. Existing rows stay clinics.
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_facility_type_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_facility_type_check
  CHECK (facility_type IN ('clinic', 'single_specialty', 'multi_specialty', 'pharmacy', 'lab')) NOT VALID;
ALTER TABLE tenants VALIDATE CONSTRAINT tenants_facility_type_check;

-- 2. The provisioning job carries the edition the operator (or the signup) chose; nothing else about a job changes.
ALTER TABLE master.provisioning_jobs ADD COLUMN edition text NOT NULL DEFAULT 'clinic'
  CHECK (edition IN ('clinic', 'pharmacy', 'lab'));

-- 3. Pharmacy preset roles (Pharmacist-in-charge, Pharmacist, Counter staff) join the six clinic presets.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_preset_code_check;
ALTER TABLE roles ADD CONSTRAINT roles_preset_code_check
  CHECK (preset_code IN ('owner','owner_doctor','doctor','nurse','receptionist','frontdesk_vitals',
                         'pharmacist_in_charge','pharmacist','counter_staff')) NOT VALID;
ALTER TABLE roles VALIDATE CONSTRAINT roles_preset_code_check;

-- 4. The receipt-printer test of the setup wizard (when the owner last printed the test page).
ALTER TABLE pharmacy_settings ADD COLUMN printer_tested_at timestamptz;

-- 5. The pharmacy plan: pharmacy stock, billing and the registers; no scheduling, queue or clinical module.
--    INACTIVE on purpose: price, seat limit and visibility are product-owner decisions (DEC-32), so it is never offered in the plan
--    catalogue and the trial-expiry job (active free plans only) never moves a clinic onto it. Provisioning subscribes a pharmacy to it by code.
--    The price 0 and seat limit 10 are PLACEHOLDERS.
WITH p AS (
  INSERT INTO master.plans (code, name, monthly_price_cents, currency, seat_limit, active, market)
  VALUES ('pharmacy_basic', 'Pharmacy Basic', 0, 'INR', 10, false, NULL)
  ON CONFLICT (code) DO NOTHING
  RETURNING id
), v AS (
  INSERT INTO master.plan_versions (plan_id, version, monthly_price_cents, seat_limit)
  SELECT id, 1, 0, 10 FROM p RETURNING plan_id, version
)
INSERT INTO master.plan_entitlements (plan_id, plan_version, module_code)
SELECT plan_id, version, m FROM v, unnest(ARRAY['pharmacy_inhouse', 'billing']) AS m;
