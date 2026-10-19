-- PLT-016: preset role bundles + a staff clinician flag that drives doctor pickers instead of role names.
-- Expand-only. Existing roles keep preset_code NULL (custom); provisioning seeds the six presets for new tenants.
ALTER TABLE roles
  ADD COLUMN IF NOT EXISTS preset_code text
    CHECK (preset_code IN ('owner','owner_doctor','doctor','nurse','receptionist','frontdesk_vitals')),
  ADD COLUMN IF NOT EXISTS preset_version int;
CREATE UNIQUE INDEX IF NOT EXISTS uq_roles_tenant_preset ON roles (tenant_id, preset_code) WHERE preset_code IS NOT NULL;

ALTER TABLE staff ADD COLUMN IF NOT EXISTS is_clinician boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_staff_active_clinicians ON staff (tenant_id) WHERE is_clinician AND status = 'active';

-- Rows the backfill could not decide cleanly, listed for the owner to confirm (GET /v1/staff/clinician-review).
CREATE TABLE IF NOT EXISTS staff_clinician_review (
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  staff_id    uuid NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
  reason      text NOT NULL CHECK (reason IN ('registration_without_doctor_role','doctor_role_without_registration')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  PRIMARY KEY (tenant_id, staff_id)
);

-- Backfill: a registration number OR a role named like doctor makes a clinician (keeps today's pickers populated);
-- exactly one of the two signals is ambiguous and goes to the review list. FORCE RLS: run as nabd_definer when available.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    SET LOCAL ROLE nabd_definer;
  END IF;
  UPDATE staff s SET is_clinician = true
    FROM roles r
   WHERE r.id = s.role_id AND NOT s.is_clinician
     AND (s.professional_registration_no IS NOT NULL OR r.name ILIKE '%doctor%');
  INSERT INTO staff_clinician_review (tenant_id, staff_id, reason)
  SELECT s.tenant_id, s.id,
         CASE WHEN s.professional_registration_no IS NOT NULL THEN 'registration_without_doctor_role'
              ELSE 'doctor_role_without_registration' END
    FROM staff s JOIN roles r ON r.id = s.role_id
   WHERE (s.professional_registration_no IS NOT NULL) <> (r.name ILIKE '%doctor%')
  ON CONFLICT DO NOTHING;
END $$;
RESET ROLE;

SELECT enforce_tenant_rls('staff_clinician_review');
