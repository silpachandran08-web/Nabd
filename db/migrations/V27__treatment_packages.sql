-- E14 Treatment Packages. Matches the wireframe's own "New package" 6-step builder, "Sell a
-- package" 6-step sale flow, and the per-instance event ledger shown in "Active patient packages".
--
-- Scope: MVP only, per the wireframe's own Package settings > Module & roadmap panel (and the
-- xlsx's Delivery Phase column, which agrees ticket-for-ticket): Instalments/pay-per-session and
-- Transfer & gifting are both explicitly labelled "Phase 2" there — not built here. Membership and
-- Family Plan package types are labelled "Phase 3" — not built here either.
--
-- Also skipped: the wireframe's multi-branch eligibility ("All branches" / "This branch only").
-- This codebase's tenant model is one clinic per tenant — there is no branches/locations table to
-- restrict against, so the field would have nothing to do.
--
-- Time-based instance status (active / grace / expired) is deliberately NOT a stored column that
-- a background job flips — no scheduled-job platform exists yet (NB-308) — it's computed on read
-- from validity_end/grace_days vs current_date. Only actor-driven terminal states (completed,
-- refunded, cancelled) are stored.

CREATE TABLE packages (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  name          text NOT NULL,
  package_type  text NOT NULL CHECK (package_type IN ('combination', 'session')),
  speciality    text,
  description   text,
  status        text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'on_sale', 'inactive')),
  price         numeric(12,2) NOT NULL CHECK (price >= 0),
  tax_inclusive boolean NOT NULL DEFAULT false,
  validity_days int NOT NULL CHECK (validity_days > 0),
  validity_starts text NOT NULL DEFAULT 'purchase_date' CHECK (validity_starts IN ('purchase_date', 'first_session')),
  grace_days    int NOT NULL DEFAULT 7 CHECK (grace_days >= 0),
  refund_note   text,
  created_by    uuid NOT NULL REFERENCES staff(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_packages_updated_at BEFORE UPDATE ON packages
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE packages ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON packages USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE package_items (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  package_id       uuid NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  item_type        text NOT NULL CHECK (item_type IN ('service_session', 'procedure', 'consultation', 'take_home_product')),
  name             text NOT NULL,
  quantity         int NOT NULL CHECK (quantity > 0),
  unit_list_price  numeric(12,2) NOT NULL CHECK (unit_list_price >= 0),
  tax_rate_percent numeric(5,2) NOT NULL DEFAULT 0,
  display_order    int NOT NULL DEFAULT 0
);
ALTER TABLE package_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_items USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_items_package ON package_items (package_id, display_order);

-- NB-155: doctors this package is restricted to. No tenant_id/RLS of its own — only ever reached
-- by joining through packages, which is itself RLS-scoped.
CREATE TABLE package_eligible_doctors (
  package_id uuid NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  doctor_id  uuid NOT NULL REFERENCES staff(id),
  PRIMARY KEY (package_id, doctor_id)
);

CREATE TABLE package_settings (
  tenant_id         uuid PRIMARY KEY REFERENCES tenants(id),
  price_floor_percent numeric(5,2) NOT NULL DEFAULT 72 CHECK (price_floor_percent BETWEEN 0 AND 100),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_package_settings_updated_at BEFORE UPDATE ON package_settings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE package_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_settings USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-152/153: a sold package. Everything priced/dated here is a snapshot taken at sale time, so
-- editing or retiring the package definition afterward never touches an instance already sold
-- (the xlsx's NB-151 AC) — no separate version-history table needed.
CREATE TABLE package_instances (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  package_id     uuid NOT NULL REFERENCES packages(id),
  patient_id     uuid NOT NULL REFERENCES patients(id),
  invoice_id     uuid NOT NULL REFERENCES invoices(id),
  package_name   text NOT NULL,
  sold_price     numeric(12,2) NOT NULL,
  sold_tax       numeric(12,2) NOT NULL,
  -- validity_starts snapshots the package's choice; when it's 'first_session', validity_start/end
  -- stay null (never expires) until the first redemption fixes them — see PackageInstanceService.
  validity_starts text NOT NULL,
  validity_days   int NOT NULL,
  validity_start date,
  validity_end   date,
  grace_days     int NOT NULL,
  status         text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'refunded', 'cancelled')),
  last_alert_tier int CHECK (last_alert_tier IN (30, 14, 7)),
  sold_by        uuid NOT NULL REFERENCES staff(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_package_instances_updated_at BEFORE UPDATE ON package_instances
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE package_instances ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_instances USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_instances_patient ON package_instances (tenant_id, patient_id);
CREATE INDEX idx_package_instances_status_expiry ON package_instances (tenant_id, status, validity_end);

CREATE TABLE package_instance_items (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  instance_id       uuid NOT NULL REFERENCES package_instances(id) ON DELETE CASCADE,
  item_type         text NOT NULL,
  name              text NOT NULL,
  quantity_total    int NOT NULL,
  quantity_consumed int NOT NULL DEFAULT 0,
  unit_list_price   numeric(12,2) NOT NULL,
  allocated_price   numeric(12,2) NOT NULL, -- this item's share of sold_price, by list-price ratio
  tax_rate_percent  numeric(5,2) NOT NULL DEFAULT 0,
  CHECK (quantity_consumed BETWEEN 0 AND quantity_total)
);
ALTER TABLE package_instance_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_instance_items USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_instance_items_instance ON package_instance_items (instance_id);

-- NB-154: a booked redemption doesn't touch quantity_consumed; only completing it does, via the
-- same guarded-UPDATE trick as pharmacy stock decrement (V25) so concurrent redemption can't
-- over-consume.
CREATE TABLE package_redemptions (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  instance_item_id  uuid NOT NULL REFERENCES package_instance_items(id),
  status            text NOT NULL DEFAULT 'booked' CHECK (status IN ('booked', 'redeemed', 'cancelled')),
  booked_by         uuid NOT NULL REFERENCES staff(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  redeemed_at       timestamptz
);
ALTER TABLE package_redemptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_redemptions USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_redemptions_item ON package_redemptions (instance_item_id, status);

-- NB-153's "package ledger": one append-only event log covers sale, payment, booking, redemption,
-- expiry-warning, extension and refund events alike — matching the single timeline the wireframe
-- itself renders per instance, instead of a separate table per event kind.
CREATE TABLE package_instance_events (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  instance_id uuid NOT NULL REFERENCES package_instances(id),
  event_type  text NOT NULL CHECK (event_type IN (
                'sold', 'payment_received', 'invoice_issued', 'session_booked', 'session_redeemed',
                'expiry_warning_sent', 'extended', 'refunded', 'cancelled')),
  note        text,
  delta       int,
  actor_id    uuid REFERENCES staff(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE package_instance_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_instance_events USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_instance_events_instance ON package_instance_events (instance_id, created_at);

-- NB-160: "consumed items valued at full list price" — paid minus that can go negative (patient
-- owes money instead of being owed a refund), matching the wireframe's refund calculator exactly.
CREATE TABLE package_refunds (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  instance_id        uuid NOT NULL REFERENCES package_instances(id),
  reason             text NOT NULL,
  used_list_value    numeric(12,2) NOT NULL,
  refund_amount      numeric(12,2) NOT NULL DEFAULT 0,
  amount_owed        numeric(12,2) NOT NULL DEFAULT 0,
  status             text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved')),
  requested_by       uuid NOT NULL REFERENCES staff(id),
  approved_by        uuid REFERENCES staff(id),
  credit_note_number text,
  created_at         timestamptz NOT NULL DEFAULT now(),
  approved_at        timestamptz,
  CHECK ((status = 'approved') = (approved_by IS NOT NULL))
);
ALTER TABLE package_refunds ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON package_refunds USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_package_refunds_instance ON package_refunds (instance_id);

CREATE SEQUENCE package_credit_note_seq;
