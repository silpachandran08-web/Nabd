SET lock_timeout = '5s';
-- PHL-005: stock is held by batch. charge_catalogue.stock_qty stays as the cached sum, kept in the same transaction by
-- pharmacy StockCommands (contract step, dropping it, waits until every reader has moved). Expand only.
CREATE TABLE stock_batches (
  id            uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id     uuid NOT NULL REFERENCES tenants (id),
  item_id       uuid NOT NULL REFERENCES charge_catalogue (id),
  batch_no      text NOT NULL CHECK (btrim(batch_no) <> ''),
  expiry_date   date,                     -- NULL = expiry unknown (migrated OPENING stock); month precision is stored as the last day
  mrp           numeric(12,2) CHECK (mrp >= 0),
  purchase_rate numeric(12,4) CHECK (purchase_rate >= 0),
  qty_on_hand   integer NOT NULL CHECK (qty_on_hand >= 0),
  location      text,
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'quarantined', 'written_off', 'returned')),
  supplier_id   uuid,
  grn_line_id   uuid,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  change_seq    bigint,
  UNIQUE NULLS NOT DISTINCT (tenant_id, item_id, batch_no, expiry_date)
);
-- FEFO pick: earliest expiry first, unknown expiry last, ties by id.
CREATE INDEX idx_stock_batches_fefo ON stock_batches (tenant_id, item_id, expiry_date NULLS LAST, id)
  WHERE qty_on_hand > 0 AND status = 'active';
CREATE INDEX idx_stock_batches_item ON stock_batches (tenant_id, item_id);
CREATE TRIGGER trg_stock_batches_change_seq BEFORE INSERT OR UPDATE ON stock_batches
  FOR EACH ROW EXECUTE FUNCTION set_change_seq();

-- Movements are per batch from now on: qty_before / qty_after are the batch's. Old rows keep batch_id NULL (the table is
-- append-only, so they cannot be back-filled). Yearly partitioning (7+ years retention) is planned for a later contract step.
ALTER TABLE pharmacy_stock_movements ADD COLUMN batch_id uuid REFERENCES stock_batches (id),
  ADD COLUMN source_type text, ADD COLUMN source_id uuid, ADD COLUMN fefo_override_reason text;
ALTER TABLE pharmacy_stock_movements DROP CONSTRAINT IF EXISTS pharmacy_stock_movements_kind_check;
ALTER TABLE pharmacy_stock_movements ADD CONSTRAINT pharmacy_stock_movements_kind_check
  CHECK (kind IN ('adjustment', 'dispense', 'restock', 'receipt', 'sale', 'return_in', 'return_out', 'write_off', 'transfer', 'opening')) NOT VALID;
ALTER TABLE pharmacy_stock_movements VALIDATE CONSTRAINT pharmacy_stock_movements_kind_check;
-- migration-lint: allow MIG-001 new nullable column, the index is the batch drawer's timeline
CREATE INDEX idx_pharmacy_stock_movements_batch ON pharmacy_stock_movements (tenant_id, batch_id, seq DESC) WHERE batch_id IS NOT NULL;

SELECT enforce_tenant_rls('stock_batches');
