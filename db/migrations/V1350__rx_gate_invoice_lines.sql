-- PHL-001 (QA-14 / MF-50, hazard H-04): a prescription-only line on an invoice must point at a signed prescription line.
-- Expand-only. is_rx is a snapshot of charge_catalogue.is_rx at sale time (the clinic sets that flag; this migration
-- does not decide which drugs are prescription-only).
ALTER TABLE invoice_line_items
  ADD COLUMN IF NOT EXISTS prescription_item_id uuid REFERENCES prescription_items(id),
  ADD COLUMN IF NOT EXISTS is_rx boolean NOT NULL DEFAULT false;

-- Backfill the snapshot for historical lines. Historical unlinked Rx lines are NOT rewritten (they stay unlinked);
-- the CSO report is docs/clinical-safety/reports/phl-001-unlinked-rx-lines.sql. FORCE RLS applies to the migrator, so
-- run as nabd_definer where it exists (V211 rule).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    SET LOCAL ROLE nabd_definer;
  END IF;
  UPDATE invoice_line_items li SET is_rx = true
    FROM charge_catalogue c
   WHERE c.tenant_id = li.tenant_id AND c.is_rx IS TRUE AND li.is_rx = false
     AND (c.id = li.charge_catalogue_id OR (li.charge_catalogue_id IS NULL AND c.code = li.charge_code));
END $$;
RESET ROLE;

-- Checked on every new or updated row from now on; validated only when no historical row breaks it.
ALTER TABLE invoice_line_items ADD CONSTRAINT invoice_line_items_rx_linked
  CHECK (NOT is_rx OR prescription_item_id IS NOT NULL) NOT VALID;

DO $$
BEGIN
  ALTER TABLE invoice_line_items VALIDATE CONSTRAINT invoice_line_items_rx_linked;
EXCEPTION WHEN check_violation THEN
  -- no PHI here: the CSO report lists the rows.
  RAISE NOTICE 'PHL-001: historical unlinked Rx lines exist; invoice_line_items_rx_linked left NOT VALID for CSO review';
END $$;

CREATE INDEX IF NOT EXISTS idx_invoice_line_items_rx_item
  ON invoice_line_items (tenant_id, prescription_item_id) WHERE prescription_item_id IS NOT NULL;

SELECT enforce_tenant_rls('invoice_line_items');
