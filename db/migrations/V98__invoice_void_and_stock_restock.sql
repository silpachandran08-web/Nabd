-- PHA-011: voiding an unpaid invoice restores pharmacy stock. Additive: widens two CHECKs and adds
-- void metadata columns; RLS/grants on both tables are unchanged (no new table).
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check CHECK (status IN ('unpaid', 'partial', 'paid', 'void'));
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS voided_at timestamptz;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS voided_by uuid REFERENCES staff(id);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS void_reason text;

ALTER TABLE pharmacy_stock_movements DROP CONSTRAINT IF EXISTS pharmacy_stock_movements_kind_check;
ALTER TABLE pharmacy_stock_movements ADD CONSTRAINT pharmacy_stock_movements_kind_check
  CHECK (kind IN ('adjustment', 'dispense', 'restock'));
