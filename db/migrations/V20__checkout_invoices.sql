-- E15 Billing, Tax & Payments
-- NB-162: Checkout & invoice generation. Deliberately narrow — payment method here is just a record
-- of what was collected (no gateway integration, that's NB-168), discount is one flat amount with no
-- approval workflow (that's NB-165's threshold/approval system), and tax is a flat per-charge
-- percentage (that's as far as NB-162 goes; the real GST/VAT compliance engine is NB-163/NB-164).

-- NB-061 shipped tax_code as a label with nothing to compute from — a rate is the one piece NB-162
-- actually needs to produce a real total.
ALTER TABLE charge_catalogue ADD COLUMN IF NOT EXISTS tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0;

CREATE TABLE invoices (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  invoice_number   text NOT NULL,
  queue_entry_id   uuid NOT NULL UNIQUE REFERENCES queue_entries(id),
  patient_id       uuid NOT NULL REFERENCES patients(id),
  doctor_id        uuid NOT NULL REFERENCES staff(id),
  subtotal         numeric(12,2) NOT NULL,
  discount         numeric(12,2) NOT NULL DEFAULT 0,
  tax              numeric(12,2) NOT NULL,
  round_off        numeric(12,2) NOT NULL DEFAULT 0,
  total            numeric(12,2) NOT NULL,
  paid             numeric(12,2) NOT NULL DEFAULT 0,
  status           text NOT NULL DEFAULT 'unpaid' CHECK (status IN ('unpaid', 'partial', 'paid')),
  created_by       uuid NOT NULL REFERENCES staff(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, invoice_number)
);
CREATE SEQUENCE invoices_number_seq;
ALTER TABLE invoices ALTER COLUMN invoice_number SET DEFAULT 'INV-' || lpad(nextval('invoices_number_seq')::text, 6, '0');
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoices USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_invoices_patient ON invoices (tenant_id, patient_id, created_at DESC);

CREATE TABLE invoice_line_items (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  invoice_id       uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  charge_code      text NOT NULL,
  charge_name      text NOT NULL,
  category         text NOT NULL,
  quantity         integer NOT NULL CHECK (quantity > 0),
  unit_price       numeric(12,2) NOT NULL,
  tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
  line_total       numeric(12,2) NOT NULL,
  display_order    integer NOT NULL DEFAULT 0
);
ALTER TABLE invoice_line_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_line_items USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_invoice_line_items_invoice ON invoice_line_items (invoice_id, display_order);

CREATE TABLE invoice_payments (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  invoice_id    uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  method        text NOT NULL CHECK (method IN ('cash', 'card', 'upi', 'other')),
  amount        numeric(12,2) NOT NULL CHECK (amount > 0),
  recorded_by   uuid NOT NULL REFERENCES staff(id),
  recorded_at   timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE invoice_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_payments USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_invoice_payments_invoice ON invoice_payments (invoice_id);
