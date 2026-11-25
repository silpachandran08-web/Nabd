SET lock_timeout = '5s';
-- PHL-056: a diagnostic lab is sold as an edition (facility type 'lab' + entitlements on the same Core), like the PHL-032 pharmacy.
-- tenants.facility_type and master.provisioning_jobs.edition already allow 'lab' (V3590).

-- 1. Lab preset roles (Lab manager, Pathologist, Front desk) join the clinic presets, Lab technician (V3540) and the pharmacy
--    presets (V3590). This re-creates the same CHECK, so it must list all thirteen codes.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_preset_code_check;
ALTER TABLE roles ADD CONSTRAINT roles_preset_code_check
  CHECK (preset_code IN ('owner','owner_doctor','doctor','nurse','receptionist','frontdesk_vitals',
                         'lab_technician','pharmacist_in_charge','pharmacist','counter_staff',
                         'lab_manager','pathologist','lab_front_desk')) NOT VALID;
ALTER TABLE roles VALIDATE CONSTRAINT roles_preset_code_check;

-- 2. The lab plans: the lab module and billing; no scheduling, queue, clinical or pharmacy module. Reports are not a plan module.
--    NEEDS PRICING: both are INACTIVE placeholders (price 0, seat limit 10, the same modules); price, seat limit, what lab_pro adds
--    and visibility are product-owner decisions. Inactive = never in the plan catalogue and never a trial-expiry target.
--    Provisioning subscribes a lab to lab_basic by code.
WITH p AS (
  INSERT INTO master.plans (code, name, monthly_price_cents, currency, seat_limit, active, market)
  VALUES ('lab_basic', 'Lab Basic', 0, 'INR', 10, false, NULL), ('lab_pro', 'Lab Pro', 0, 'INR', 10, false, NULL)
  ON CONFLICT (code) DO NOTHING
  RETURNING id
), v AS (
  INSERT INTO master.plan_versions (plan_id, version, monthly_price_cents, seat_limit)
  SELECT id, 1, 0, 10 FROM p RETURNING plan_id, version
)
INSERT INTO master.plan_entitlements (plan_id, plan_version, module_code)
SELECT plan_id, version, m FROM v, unnest(ARRAY['lab', 'billing']) AS m;

-- 3. What the lab setup wizard records: accreditation (NABL number, optional), the pathologist and their registration number,
--    the lines printed under the lab's name on its letterhead, and when the label printer test page was last printed.
CREATE TABLE lab_settings (
  tenant_id                   uuid PRIMARY KEY REFERENCES tenants (id),
  accreditation_no            text CHECK (length(accreditation_no) <= 40),
  pathologist_staff_id        uuid REFERENCES staff (id),
  pathologist_registration_no text CHECK (length(pathologist_registration_no) <= 80),
  letterhead_lines            text CHECK (length(letterhead_lines) <= 400),
  printer_tested_at           timestamptz,
  updated_at                  timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('lab_settings');
