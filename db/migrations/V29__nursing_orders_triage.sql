-- E13 Nursing, Orders & Triage.
--
-- NB-143 (finishing "In progress"): queue_entries already carries priority/priority_reason (an
-- earlier pass) — this adds who/when flagged it and the doctor's acknowledgement, the missing
-- "alerts the doctor" half of the AC (a passive dot on the Consult page isn't an alert).
ALTER TABLE queue_entries
  ADD COLUMN priority_flagged_by uuid REFERENCES staff(id),
  ADD COLUMN priority_flagged_at timestamptz,
  ADD COLUMN priority_acknowledged_by uuid REFERENCES staff(id),
  ADD COLUMN priority_acknowledged_at timestamptz;

-- NB-144/149 (clinical triage inbox, task handoff/escalation) are not built here — both depend on
-- NB-197's shared WhatsApp inbox, which doesn't exist yet, same gap already hit in E09/E16.

-- NB-145: administration orders (what to give) and an append-only, immutable record of what
-- actually happened — split so the order stays editable/cancellable right up to the point it's
-- acted on, while the outcome, once written, never changes. One administration per order — a
-- refused order is retried as a fresh order, not a second attempt on the same row.
CREATE TABLE administration_orders (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  queue_entry_id uuid NOT NULL REFERENCES queue_entries(id),
  patient_id     uuid NOT NULL REFERENCES patients(id),
  ordered_by     uuid NOT NULL REFERENCES staff(id),
  drug_name      text NOT NULL,
  dose           text NOT NULL,
  route          text NOT NULL CHECK (route IN ('IM', 'IV', 'SC', 'infusion', 'oral', 'topical')),
  site           text,
  created_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE administration_orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON administration_orders USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_administration_orders_queue_entry ON administration_orders (queue_entry_id);

-- Immutable by convention (the application never issues UPDATE/DELETE here) plus UNIQUE(order_id)
-- — a second row for the same order is a schema violation, not just a bug to catch in review.
-- The five-rights check (right patient/drug/dose/route/time) is a UI confirmation step, not a
-- column — there's nothing in a drug name/dose/route to mechanically verify against.
CREATE TABLE administration_records (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  order_id        uuid NOT NULL UNIQUE REFERENCES administration_orders(id),
  action          text NOT NULL CHECK (action IN ('administered', 'refused')),
  recorded_by     uuid NOT NULL REFERENCES staff(id), -- the nurse filing this record, either way
  witnessed_by    uuid REFERENCES staff(id),          -- required only for 'administered'
  refuse_reason   text,
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  CHECK ((action = 'administered') = (witnessed_by IS NOT NULL)),
  CHECK ((action = 'refused') = (refuse_reason IS NOT NULL))
);
ALTER TABLE administration_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON administration_records USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-146: procedures scheduled for today. charge_code/name/amounts are a snapshot at order time
-- (same convention as invoice_line_items — no FK to charge_catalogue), so a later catalogue price
-- change never rewrites an order already sitting on today's worklist. "Completion posts a billable
-- event" (the AC) is CheckoutContextResponse surfacing unbilled completed rows so Fast Checkout
-- pre-loads them — see CheckoutRepository — not a second, parallel invoicing path.
CREATE TABLE procedure_orders (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  queue_entry_id   uuid NOT NULL REFERENCES queue_entries(id),
  patient_id       uuid NOT NULL REFERENCES patients(id),
  ordered_by       uuid NOT NULL REFERENCES staff(id),
  charge_code      text NOT NULL,
  charge_name      text NOT NULL,
  base_amount      numeric(12,2) NOT NULL,
  tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
  prep_notes       text,
  consent_note     text,
  status           text NOT NULL DEFAULT 'ordered' CHECK (status IN ('ordered', 'prepped', 'completed', 'cancelled')),
  billed           boolean NOT NULL DEFAULT false,
  completed_by     uuid REFERENCES staff(id),
  completed_at     timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE procedure_orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procedure_orders USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_procedure_orders_queue_entry ON procedure_orders (queue_entry_id);

-- NB-147 (package session administration by nurse) and NB-148 (completed activity log) need no
-- schema: NB-147 reuses E14's package_redemptions/package_instance_items exactly as they stand
-- (RBAC exposure only, see the "packages" module grant); NB-148 is a read-only query across
-- vitals, administration_records and this migration's priority_acknowledged_at, not a new table.
