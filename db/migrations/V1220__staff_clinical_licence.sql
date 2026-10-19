-- CLN-005 (T-058): licence data the signing rule reads. Additive; staff already has tenant RLS (new columns inherit it).
-- is_clinician is owned by PLT-016 (iam). Same definition as PLT-016's spec, written IF NOT EXISTS so whichever of the
-- two migrations runs first creates it and the other is a no-op. iam (staff module) is the only writer from the app.
-- licence_expires_on: the registration's expiry date (null = no expiry recorded, T-310 will supply verified dates).
-- licence_override_until: an owner's time-boxed, audited exception that lets an expired licence keep signing.
SET lock_timeout = '5s';

ALTER TABLE staff ADD COLUMN IF NOT EXISTS is_clinician boolean NOT NULL DEFAULT false;
ALTER TABLE staff ADD COLUMN IF NOT EXISTS licence_expires_on date;
ALTER TABLE staff ADD COLUMN IF NOT EXISTS licence_override_until timestamptz;

-- Target of the composite FK prescriptions (tenant_id, signed_by) -> staff (V1221). staff is small; plain (not
-- CONCURRENTLY) because Flyway runs migrations in a transaction.
CREATE UNIQUE INDEX IF NOT EXISTS uq_staff_tenant_id_id ON staff (tenant_id, id);

-- Backfill so existing prescribers keep signing: a doctor-like role or the built-in Owner role (owner-doctors) with a
-- registration number on file. Anyone else stays false until an owner marks them via PUT /v1/staff/{id}/clinical-licence.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    UPDATE staff s SET is_clinician = true
      FROM roles r
     WHERE s.tenant_id = t.id AND r.id = s.role_id AND NOT s.is_clinician
       AND s.professional_registration_no IS NOT NULL
       AND (r.name ILIKE '%doctor%' OR (r.built_in AND r.name = 'Owner'));
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
