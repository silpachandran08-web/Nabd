SET lock_timeout = '5s';
-- PHL-003: pharmacy is the only stock writer. 'receipt' = stock received (StockCommands.receive).
ALTER TABLE pharmacy_stock_movements DROP CONSTRAINT IF EXISTS pharmacy_stock_movements_kind_check;
ALTER TABLE pharmacy_stock_movements ADD CONSTRAINT pharmacy_stock_movements_kind_check
  CHECK (kind IN ('adjustment', 'dispense', 'restock', 'receipt')) NOT VALID;
ALTER TABLE pharmacy_stock_movements VALIDATE CONSTRAINT pharmacy_stock_movements_kind_check;

-- ADR-07 policies (per-command, FORCE) on the two pre-ADR-07 pharmacy tables; idempotent.
SELECT enforce_tenant_rls('pharmacy_settings');
SELECT enforce_tenant_rls('pharmacy_stock_movements');
