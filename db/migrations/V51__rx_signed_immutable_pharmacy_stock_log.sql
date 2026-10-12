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
  -- V3-002: check every parent the row touches. An UPDATE that re-parents an item must be refused when the
  -- OLD parent is signed (moving items out of a signed Rx), as well as when the NEW parent is (moving one in).
  -- No parent row visible = the parent is being deleted (ON DELETE CASCADE of a draft) or is outside this tenant: allow.
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT status INTO parent_status FROM prescriptions WHERE id = OLD.prescription_id;
    IF parent_status = 'signed' THEN
      RAISE EXCEPTION 'prescription is signed: its items cannot be changed (% on prescription_items)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT status INTO parent_status FROM prescriptions WHERE id = NEW.prescription_id;
    IF parent_status = 'signed' THEN
      RAISE EXCEPTION 'prescription is signed: its items cannot be changed (% on prescription_items)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
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

-- V3-002: deleting a signed header would cascade to its items (the items trigger then sees no parent and allows it),
-- and row triggers do not fire on TRUNCATE. Refuse both, for the header and for the items.
CREATE OR REPLACE FUNCTION prescriptions_signed_no_delete() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'signed' THEN
    RAISE EXCEPTION 'prescription is signed and cannot be deleted'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prescriptions_signed_no_delete ON prescriptions;
CREATE TRIGGER trg_prescriptions_signed_no_delete BEFORE DELETE ON prescriptions
  FOR EACH ROW EXECUTE FUNCTION prescriptions_signed_no_delete();

CREATE OR REPLACE FUNCTION prescriptions_no_truncate() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'TRUNCATE is not permitted on %: signed prescriptions are final', TG_TABLE_NAME
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prescriptions_no_truncate ON prescriptions;
CREATE TRIGGER trg_prescriptions_no_truncate BEFORE TRUNCATE ON prescriptions
  FOR EACH STATEMENT EXECUTE FUNCTION prescriptions_no_truncate();
DROP TRIGGER IF EXISTS trg_prescription_items_no_truncate ON prescription_items;
CREATE TRIGGER trg_prescription_items_no_truncate BEFORE TRUNCATE ON prescription_items
  FOR EACH STATEMENT EXECUTE FUNCTION prescriptions_no_truncate();

-- 2. Pharmacy stock movements: one immutable row per stock change (manual adjustment or checkout dispense).
--    charge_catalogue.stock_qty stays the current balance; this table is the "why/who/when" and is what
--    the balance can be reconciled against. Rows are never updated or deleted.
CREATE TABLE IF NOT EXISTS pharmacy_stock_movements (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- V3-003: occurred_at defaults to the transaction START, which is not the order the item row lock was taken in.
  -- seq is assigned at INSERT, always after the item row lock, so it follows the qty_before/qty_after chain exactly.
  seq         bigint GENERATED ALWAYS AS IDENTITY,
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
  ON pharmacy_stock_movements (tenant_id, item_id, seq DESC);

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
