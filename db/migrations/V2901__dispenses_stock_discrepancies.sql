SET lock_timeout = '5s';
-- PHL-018: a hybrid clinic dispenses the visit's in-house Rx items at checkout. One lightweight dispenses row per checkout
-- (source nabd_rx, no batch choice). When the shelf holds less than the bill asks for, the receptionist may "dispense anyway"
-- with a reason: stock stays at zero or above and the missing units become a stock_discrepancies row for the next count.
CREATE TABLE dispenses (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid NOT NULL REFERENCES tenants (id),
  invoice_id uuid NOT NULL REFERENCES invoices (id),
  source     text NOT NULL CHECK (source IN ('nabd_rx')),
  lines      jsonb NOT NULL,
  created_by uuid NOT NULL REFERENCES staff (id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispenses_invoice ON dispenses (tenant_id, invoice_id);
SELECT enforce_tenant_rls('dispenses');

CREATE TABLE stock_discrepancies (
  id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                  uuid NOT NULL REFERENCES tenants (id),
  item_id                    uuid NOT NULL REFERENCES charge_catalogue (id),
  qty                        int NOT NULL CHECK (qty > 0),
  reason                     text NOT NULL CHECK (btrim(reason) <> ''),
  dispense_id                uuid REFERENCES dispenses (id),
  resolved_by_stock_take_id  uuid,
  created_by                 uuid NOT NULL REFERENCES staff (id),
  created_at                 timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_discrepancies_item ON stock_discrepancies (tenant_id, item_id, created_at DESC);
SELECT enforce_tenant_rls('stock_discrepancies');
