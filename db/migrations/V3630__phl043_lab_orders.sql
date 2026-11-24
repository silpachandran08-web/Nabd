SET lock_timeout = '5s';
-- PHL-043: lab orders placed from the consult (module lab, behind the `lab` entitlement). One order holds one row per test the
-- lab performs; a panel is expanded into its tests (panel_id set on each). Collection (PHL-045), the worklist (PHL-044), results
-- and release move `status` on; this story writes 'ordered' and 'cancelled' only.
-- Beyond the spec's column list: fasting_hours (the doctor's fasting requirement, read by collection) and the cancel columns.
-- partner_lab_id and referring_doctor_id have no register to point at yet (PHL-055, referrals), so they carry no foreign key.
CREATE TABLE lab_orders (
  id                  uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id           uuid NOT NULL REFERENCES tenants (id),
  patient_id          uuid NOT NULL REFERENCES patients (id),
  encounter_id        uuid,
  queue_entry_id      uuid REFERENCES queue_entries (id),
  ordering_doctor_id  uuid REFERENCES staff (id),
  referring_doctor_id uuid,
  source              text NOT NULL CHECK (source IN ('consult', 'walk_in', 'referral', 'partner')),
  priority            text NOT NULL CHECK (priority IN ('routine', 'urgent', 'stat')),
  destination_kind    text NOT NULL CHECK (destination_kind IN ('inhouse', 'partner', 'external')),
  partner_lab_id      uuid,
  status              text NOT NULL DEFAULT 'ordered' CHECK (status IN ('ordered', 'partially_collected', 'collected', 'in_progress',
                        'partially_resulted', 'resulted', 'validated', 'released', 'cancelled')),
  clinical_note       text CHECK (length(clinical_note) <= 500),
  fasting_hours       int CHECK (fasting_hours BETWEEN 1 AND 24),
  ordered_at          timestamptz NOT NULL DEFAULT now(),
  cancelled_at        timestamptz,
  cancelled_by        uuid REFERENCES staff (id),
  cancel_reason       text CHECK (length(cancel_reason) <= 200),
  change_seq          bigint,
  updated_at          timestamptz NOT NULL DEFAULT now(), -- set_change_seq() writes it on every update
  CHECK (destination_kind <> 'partner' OR partner_lab_id IS NOT NULL),
  CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL))
);
CREATE TRIGGER trg_lab_orders_change_seq BEFORE INSERT OR UPDATE ON lab_orders
  FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE INDEX idx_lab_orders_worklist ON lab_orders (tenant_id, status, priority, ordered_at);
CREATE INDEX idx_lab_orders_visit ON lab_orders (tenant_id, queue_entry_id) WHERE queue_entry_id IS NOT NULL;
CREATE INDEX idx_lab_orders_doctor ON lab_orders (tenant_id, ordering_doctor_id, ordered_at DESC);
SELECT enforce_tenant_rls('lab_orders');

-- price_snapshot: the price-list price when ordered. A panel is one charge: its first test row carries the panel price and the
-- others 0.00, so the sum over an order is its total. charge_line_ref: the billing pending charge (visit_pending_charges.id)
-- the row was billed through; every row of a panel shares the panel's one charge; NULL when nothing was charged.
CREATE TABLE lab_order_items (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  order_id        uuid NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
  test_id         uuid NOT NULL REFERENCES lab_tests (id),
  panel_id        uuid REFERENCES lab_panels (id),
  status          text NOT NULL DEFAULT 'ordered' CHECK (status IN ('ordered', 'collected', 'in_progress', 'resulted', 'validated',
                    'released', 'cancelled')),
  price_snapshot  numeric(12,2) NOT NULL CHECK (price_snapshot >= 0),
  charge_line_ref uuid,
  display_order   int NOT NULL CHECK (display_order > 0),
  UNIQUE (order_id, test_id)
);
CREATE INDEX idx_lab_order_items_order ON lab_order_items (tenant_id, order_id, display_order);
SELECT enforce_tenant_rls('lab_order_items');

-- Billing's side (module billing): charges another module asked to be put on a visit's bill. The checkout offers the pending ones
-- (pre-loaded like a finished procedure) and marks them billed with the invoice; the asking module removes them through
-- billing.api.VisitCharges when its order is cancelled. source_ref is the asking module's own id (lab: lab_orders.id).
CREATE TABLE visit_pending_charges (
  id             uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  queue_entry_id uuid NOT NULL REFERENCES queue_entries (id),
  source         text NOT NULL CHECK (source IN ('lab')),
  source_ref     uuid NOT NULL,
  charge_item_id uuid NOT NULL REFERENCES charge_catalogue (id),
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'billed', 'removed')),
  invoice_id     uuid REFERENCES invoices (id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  settled_at     timestamptz
);
CREATE INDEX idx_visit_pending_charges_visit ON visit_pending_charges (tenant_id, queue_entry_id) WHERE status = 'pending';
CREATE INDEX idx_visit_pending_charges_source ON visit_pending_charges (tenant_id, source, source_ref);
SELECT enforce_tenant_rls('visit_pending_charges');
