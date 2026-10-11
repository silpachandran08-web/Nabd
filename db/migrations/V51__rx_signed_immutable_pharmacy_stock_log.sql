-- V51: (1) a signed prescription is final at the database level (V1-004), (2) immutable pharmacy stock
-- movement log (V2-004), (3) TRUNCATE guard for the allergy history table (V1-009).
--
-- Why V51 and not V49: V50 is already applied on environments, and Flyway here runs without out-of-order,
-- so a lower number added now would be "ignored" and fail validation. V50 itself is not edited; everything
-- below is new objects only (expand-only), written to be safe to re-run.

-- 1. Signed prescriptions are final. The application takes a row lock on the prescription in both the
--    save and the sign path (PrescriptionService); this is the backstop that makes "a signed prescription
--    can never change" true even if a future code path forgets the lock.
CREATE OR REPLACE FUNCTION prescription_items_locked_when_signed() RETURNS trigger AS $$
DECLARE
  parent_status text;
BEGIN
  SELECT status INTO parent_status FROM prescriptions
   WHERE id = (CASE WHEN TG_OP = 'DELETE' THEN OLD.prescription_id ELSE NEW.prescription_id END);
  -- No parent row visible = the parent is being deleted (ON DELETE CASCADE) or is outside this tenant: allow.
  IF parent_status = 'signed' THEN
    RAISE EXCEPTION 'prescription is signed: its items cannot be changed (% on prescription_items)', TG_OP
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prescription_items_signed_lock ON prescription_items;
CREATE TRIGGER trg_prescription_items_signed_lock BEFORE INSERT OR UPDATE OR DELETE ON prescription_items
  FOR EACH ROW EXECUTE FUNCTION prescription_items_locked_when_signed();

CREATE OR REPLACE FUNCTION prescriptions_signed_is_final() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'signed' THEN
    RAISE EXCEPTION 'prescription is signed and cannot be modified'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prescriptions_signed_final ON prescriptions;
CREATE TRIGGER trg_prescriptions_signed_final BEFORE UPDATE ON prescriptions
  FOR EACH ROW EXECUTE FUNCTION prescriptions_signed_is_final();

-- 2. Pharmacy stock movements: one immutable row per stock change (manual adjustment or checkout dispense).
--    charge_catalogue.stock_qty stays the current balance; this table is the "why/who/when" and is what
--    the balance can be reconciled against. Rows are never updated or deleted.
CREATE TABLE IF NOT EXISTS pharmacy_stock_movements (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  item_id     uuid NOT NULL REFERENCES charge_catalogue(id),
  kind        text NOT NULL CHECK (kind IN ('adjustment', 'dispense')),
  delta       integer NOT NULL CHECK (delta <> 0),
  qty_before  integer NOT NULL CHECK (qty_before >= 0),
  qty_after   integer NOT NULL CHECK (qty_after >= 0),
  reason      text NOT NULL CHECK (btrim(reason) <> ''),
  invoice_id  uuid REFERENCES invoices(id),
  actor_id    uuid REFERENCES staff(id),            -- null only for system-driven rows
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CHECK (qty_after = qty_before + delta)
);
CREATE INDEX IF NOT EXISTS idx_pharmacy_stock_movements_item
  ON pharmacy_stock_movements (tenant_id, item_id, occurred_at DESC);

ALTER TABLE pharmacy_stock_movements ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'pharmacy_stock_movements' AND policyname = 'tenant_isolation') THEN
    CREATE POLICY tenant_isolation ON pharmacy_stock_movements
      USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
  END IF;
END $$;

CREATE OR REPLACE FUNCTION pharmacy_stock_movements_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'pharmacy_stock_movements is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_pharmacy_stock_movements_no_update ON pharmacy_stock_movements;
CREATE TRIGGER trg_pharmacy_stock_movements_no_update BEFORE UPDATE ON pharmacy_stock_movements
  FOR EACH ROW EXECUTE FUNCTION pharmacy_stock_movements_immutable();
DROP TRIGGER IF EXISTS trg_pharmacy_stock_movements_no_delete ON pharmacy_stock_movements;
CREATE TRIGGER trg_pharmacy_stock_movements_no_delete BEFORE DELETE ON pharmacy_stock_movements
  FOR EACH ROW EXECUTE FUNCTION pharmacy_stock_movements_immutable();
DROP TRIGGER IF EXISTS trg_pharmacy_stock_movements_no_truncate ON pharmacy_stock_movements;
CREATE TRIGGER trg_pharmacy_stock_movements_no_truncate BEFORE TRUNCATE ON pharmacy_stock_movements
  FOR EACH STATEMENT EXECUTE FUNCTION pharmacy_stock_movements_immutable();

-- 3. V50's append-only trigger on the allergy history covers UPDATE and DELETE only; TRUNCATE wiped it.
DROP TRIGGER IF EXISTS trg_patient_allergy_events_no_truncate ON patient_allergy_events;
CREATE TRIGGER trg_patient_allergy_events_no_truncate BEFORE TRUNCATE ON patient_allergy_events
  FOR EACH STATEMENT EXECUTE FUNCTION patient_allergy_events_immutable();
