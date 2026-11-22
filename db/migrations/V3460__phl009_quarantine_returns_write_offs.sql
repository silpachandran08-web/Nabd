SET lock_timeout = '5s';
-- PHL-009: quarantined stock leaves either back to the supplier (a return note with a gapless debit-note number) or as a
-- write-off with a record. The stock itself moves through pharmacy StockCommands as immutable 'return_out' / 'write_off'
-- movements (kinds and the 'quarantined' batch status exist since V2890); these tables are the documents. Expand only.
-- There is no supplier master yet (PHL-007), so the supplier is named and supplier_id is kept as given.
CREATE TABLE supplier_returns (
  id                     uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id              uuid NOT NULL REFERENCES tenants (id),
  supplier_id            uuid,
  supplier_name_snapshot text NOT NULL CHECK (btrim(supplier_name_snapshot) <> ''),
  debit_note_number      text NOT NULL,
  status                 text NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'cancelled')),
  value                  numeric(14,2) NOT NULL CHECK (value >= 0),
  reason                 text CHECK (length(reason) <= 500),
  created_by             uuid NOT NULL REFERENCES staff (id),
  created_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, debit_note_number)
);
CREATE INDEX idx_supplier_returns_recent ON supplier_returns (tenant_id, created_at DESC);
SELECT enforce_tenant_rls('supplier_returns');

CREATE TABLE supplier_return_lines (
  id         uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id  uuid NOT NULL REFERENCES tenants (id),
  return_id  uuid NOT NULL REFERENCES supplier_returns (id),
  line_order int NOT NULL,
  batch_id   uuid NOT NULL REFERENCES stock_batches (id),
  item_id    uuid NOT NULL REFERENCES charge_catalogue (id),
  qty        int NOT NULL CHECK (qty > 0),
  rate       numeric(12,4) NOT NULL CHECK (rate >= 0),   -- the batch's purchase rate when returned (0 = none on file)
  value      numeric(14,2) NOT NULL CHECK (value >= 0),
  UNIQUE (return_id, batch_id)
);
CREATE INDEX idx_supplier_return_lines_return ON supplier_return_lines (tenant_id, return_id, line_order);
SELECT enforce_tenant_rls('supplier_return_lines');

CREATE TABLE stock_write_offs (
  id               uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id        uuid NOT NULL REFERENCES tenants (id),
  method           text NOT NULL CHECK (method IN ('destroyed', 'damaged', 'lost')),
  note             text CHECK (length(note) <= 500),
  value            numeric(14,2) NOT NULL CHECK (value >= 0),
  witness_staff_id uuid REFERENCES staff (id),            -- the second staff member; mandatory when a line is a controlled class
  approved_by      uuid NOT NULL REFERENCES staff (id),   -- the holder of pharmacy:adjust_approve who wrote it off
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (witness_staff_id IS NULL OR witness_staff_id <> approved_by)
);
CREATE INDEX idx_stock_write_offs_recent ON stock_write_offs (tenant_id, created_at DESC);
SELECT enforce_tenant_rls('stock_write_offs');

-- register_type: the statutory register of the item's class when it was written off (NULL = not a controlled class). A line
-- with a register_type is the destruction entry of that register; the 'write_off' movement carries maker and witness.
CREATE TABLE stock_write_off_lines (
  id            uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id     uuid NOT NULL REFERENCES tenants (id),
  write_off_id  uuid NOT NULL REFERENCES stock_write_offs (id),
  line_order    int NOT NULL,
  batch_id      uuid NOT NULL REFERENCES stock_batches (id),
  item_id       uuid NOT NULL REFERENCES charge_catalogue (id),
  qty           int NOT NULL CHECK (qty > 0),
  rate          numeric(12,4) NOT NULL CHECK (rate >= 0),
  value         numeric(14,2) NOT NULL CHECK (value >= 0),
  register_type text,
  UNIQUE (write_off_id, batch_id)
);
CREATE INDEX idx_stock_write_off_lines_register ON stock_write_off_lines (tenant_id, register_type, id) WHERE register_type IS NOT NULL;
SELECT enforce_tenant_rls('stock_write_off_lines');

-- Debit notes draw from the FIN-006 gapless series (one row per facility, kind and financial year).
ALTER TABLE invoice_series DROP CONSTRAINT IF EXISTS invoice_series_kind_check;
ALTER TABLE invoice_series ADD CONSTRAINT invoice_series_kind_check
  CHECK (kind IN ('invoice', 'credit_note', 'receipt', 'debit_note')) NOT VALID;
ALTER TABLE invoice_series VALIDATE CONSTRAINT invoice_series_kind_check;
