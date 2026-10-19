-- CLN-005: the signer is stored on the prescription, not read back from audit_log (MF-52). Expand step:
-- columns, composite FK and the CHECK are added NOT VALID here; V1222 backfills and validates them.
SET lock_timeout = '5s';

ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS signed_by uuid;
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS signed_by_registration_no text;

ALTER TABLE prescriptions ADD CONSTRAINT fk_prescriptions_signed_by
  FOREIGN KEY (tenant_id, signed_by) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE prescriptions ADD CONSTRAINT chk_prescriptions_signed_by
  CHECK ((status = 'signed') = (signed_by IS NOT NULL)) NOT VALID;

-- Rows the backfill could not attribute from an audit row: signed_by was set to the visit doctor and flagged here,
-- so a reviewer can tell an inferred signer from a recorded one. Ids only, no PHI.
CREATE TABLE IF NOT EXISTS prescription_signer_backfill (
  prescription_id uuid PRIMARY KEY REFERENCES prescriptions(id),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  source          text NOT NULL CHECK (source IN ('visit_doctor')),
  created_at      timestamptz NOT NULL DEFAULT now()
);

-- One tenant's backfill. Not SECURITY DEFINER: the caller sets app.tenant_id (RLS) and must have disabled
-- trg_prescriptions_signed_final, which refuses any UPDATE of a signed row. Returns the rows it attributed.
CREATE OR REPLACE FUNCTION backfill_prescription_signers(p_tenant uuid) RETURNS int
  LANGUAGE plpgsql AS $$
DECLARE
  n int;
  m int;
BEGIN
  -- latest prescription.signed audit row per prescription, when that actor is still a staff row of this tenant
  UPDATE prescriptions p SET signed_by = a.actor_id
    FROM (SELECT DISTINCT ON (entity_id) entity_id, actor_id FROM audit_log
           WHERE tenant_id = p_tenant AND action = 'prescription.signed' AND entity_type = 'prescription'
             AND actor_type = 'staff' AND actor_id IS NOT NULL
           ORDER BY entity_id, id DESC) a
   WHERE p.tenant_id = p_tenant AND p.id = a.entity_id AND p.status = 'signed' AND p.signed_by IS NULL
     AND EXISTS (SELECT 1 FROM staff s WHERE s.tenant_id = p_tenant AND s.id = a.actor_id);
  GET DIAGNOSTICS n = ROW_COUNT;

  INSERT INTO prescription_signer_backfill (prescription_id, tenant_id, source)
  SELECT id, tenant_id, 'visit_doctor' FROM prescriptions
   WHERE tenant_id = p_tenant AND status = 'signed' AND signed_by IS NULL
  ON CONFLICT (prescription_id) DO NOTHING;
  UPDATE prescriptions SET signed_by = doctor_id
   WHERE tenant_id = p_tenant AND status = 'signed' AND signed_by IS NULL;
  GET DIAGNOSTICS m = ROW_COUNT;

  -- the number on file now is the best available record for a legacy row (the print fell back to it already)
  UPDATE prescriptions p SET signed_by_registration_no = s.professional_registration_no
    FROM staff s
   WHERE p.tenant_id = p_tenant AND s.tenant_id = p_tenant AND s.id = p.signed_by
     AND p.status = 'signed' AND p.signed_by_registration_no IS NULL AND s.professional_registration_no IS NOT NULL;
  RETURN n + m;
END $$;
REVOKE ALL ON FUNCTION backfill_prescription_signers(uuid) FROM PUBLIC;

SELECT enforce_tenant_rls('prescription_signer_backfill');
