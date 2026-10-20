-- CLN-005: backfill prescriptions.signed_by (latest prescription.signed audit row, else the visit doctor + a flag in
-- prescription_signer_backfill), then validate the FK and the signed <=> signed_by CHECK added in V1221.
-- shortcut: one UPDATE per tenant instead of 5k-row batches; fine at today's volumes (thousands of Rx per tenant),
-- split into batched runs before a tenant passes ~1M signed prescriptions.
SET lock_timeout = '5s';

-- V51's trigger refuses any UPDATE of a signed row; this backfill is the one sanctioned exception.
ALTER TABLE prescriptions DISABLE TRIGGER trg_prescriptions_signed_final;

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    PERFORM backfill_prescription_signers(t.id);
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

ALTER TABLE prescriptions ENABLE TRIGGER trg_prescriptions_signed_final;

ALTER TABLE prescriptions VALIDATE CONSTRAINT fk_prescriptions_signed_by;
ALTER TABLE prescriptions VALIDATE CONSTRAINT chk_prescriptions_signed_by;
