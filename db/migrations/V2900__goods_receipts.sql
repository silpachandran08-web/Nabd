SET lock_timeout = '5s';
-- PHL-007: a direct supplier bill (goods receipt without a purchase order). A GRN against a PO (PHL-025..028) reuses these tables.
-- There is no supplier master yet, so a bill is unique per (supplier_key, bill_number): supplier_key is the supplier id when sent,
-- else the trimmed lower-case supplier name. batch_id stays NULL until the batch tables of PHL-005 exist.
CREATE TABLE goods_receipts (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              uuid NOT NULL REFERENCES tenants (id),
  supplier_id            uuid,
  supplier_name_snapshot text NOT NULL,
  supplier_key           text NOT NULL,
  purchase_order_id      uuid,
  bill_number            text NOT NULL,
  bill_date              date NOT NULL,
  status                 text NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
  subtotal               numeric(14,2) NOT NULL CHECK (subtotal >= 0),
  line_count             int NOT NULL CHECK (line_count > 0),
  posted_by              uuid NOT NULL REFERENCES staff (id),
  posted_at              timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, supplier_key, bill_number)
);
SELECT enforce_tenant_rls('goods_receipts');

CREATE TABLE goods_receipt_lines (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  receipt_id     uuid NOT NULL REFERENCES goods_receipts (id),
  line_order     int NOT NULL,
  item_id        uuid NOT NULL REFERENCES charge_catalogue (id),
  batch_id       uuid,
  batch_no       text NOT NULL,
  expiry_date    date NOT NULL,
  mrp            numeric(12,2) NOT NULL CHECK (mrp >= 0),
  rate           numeric(12,2) NOT NULL CHECK (rate >= 0),
  qty            int NOT NULL CHECK (qty > 0),
  free_qty       int NOT NULL DEFAULT 0 CHECK (free_qty >= 0),
  tax_rate       numeric(5,2) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0),
  effective_rate numeric(12,2) NOT NULL CHECK (effective_rate >= 0),
  line_total     numeric(14,2) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX idx_goods_receipt_lines_receipt ON goods_receipt_lines (tenant_id, receipt_id, line_order);
SELECT enforce_tenant_rls('goods_receipt_lines');
