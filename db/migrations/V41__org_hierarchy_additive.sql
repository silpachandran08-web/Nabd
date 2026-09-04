-- NB-358 (Arogya Fabric restructuring, Phase 2): additive organisation-hierarchy and commercial-axis
-- schema from the architecture doc's §6.3. Confirmed mapping (see the restructuring plan): doc L1
-- Tenant = our owners, doc L2 Brand = our brands (exact match already), doc L3 Facility = our
-- tenants (already the RLS boundary), doc L4 Department = our departments. Nothing here changes the
-- isolation grain or any existing runtime behaviour — every column is nullable-or-defaulted and no
-- application code reads any of it yet (that's later phases, once a real feature needs it).

-- ── L3 Facility: which of the doc's three facility types this clinic is, and how it bills ──
ALTER TABLE tenants ADD COLUMN facility_type text NOT NULL DEFAULT 'clinic'
  CHECK (facility_type IN ('clinic', 'single_specialty', 'multi_specialty'));
-- Doc's stated platform default is 'consolidated' (FIG 6.3a), but this session's already-shipped
-- interim-billing checkout (NB-355/356) is structurally 'per_department' — defaulting to that here
-- keeps every existing tenant's actual behaviour, not the doc's ecosystem-wide default, honest.
ALTER TABLE tenants ADD COLUMN billing_mode text NOT NULL DEFAULT 'per_department'
  CHECK (billing_mode IN ('consolidated', 'per_department'));

-- ── L4 Department: the doc's kind/specialty_code, alongside the specialty-ish `name` this session
-- already built (V37). A clinic's single department keeps kind='clinical' by default. ──
ALTER TABLE departments ADD COLUMN kind text NOT NULL DEFAULT 'clinical'
  CHECK (kind IN ('clinical', 'diagnostic', 'pharmacy', 'support', 'ward'));
ALTER TABLE departments ADD COLUMN specialty_code text;

-- ── L5 Service point: optional room/chair/station/counter/bed inside a department. ──
CREATE TABLE service_points (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  department_id uuid NOT NULL REFERENCES departments(id),
  kind          text NOT NULL CHECK (kind IN ('room', 'chair', 'station', 'counter', 'bed')),
  label         text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_service_points_updated_at BEFORE UPDATE ON service_points
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE service_points ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_points
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ── Commercial axis: Legal entity -> Tax registration -> Invoice series (doc §6.3 FIG 6.3a). ──
-- Hangs off owner_id, not tenant_id -- same "no RLS above the clinic-scoped tables" precedent as
-- owners/brands themselves (see V6): access is an explicit WHERE owner_id = ?, not session-context
-- filtering, because a legal entity can be shared across every facility under that owner.
CREATE TABLE legal_entities (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id    uuid NOT NULL REFERENCES owners(id),
  name        text NOT NULL,
  entity_type text NOT NULL CHECK (entity_type IN ('company', 'llp', 'proprietor', 'franchisee')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_legal_entities_updated_at BEFORE UPDATE ON legal_entities
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_legal_entities_owner ON legal_entities (owner_id);

CREATE TABLE tax_registrations (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  legal_entity_id   uuid NOT NULL REFERENCES legal_entities(id),
  jurisdiction_code text NOT NULL,
  gstin_or_vat_no   text,
  registered        boolean NOT NULL DEFAULT false,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_tax_registrations_updated_at BEFORE UPDATE ON tax_registrations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_tax_registrations_legal_entity ON tax_registrations (legal_entity_id);

-- A facility bills through exactly one tax registration (FIG 6.3a) -- nullable: most tenants have
-- no GST/VAT registration on file yet, and nothing requires one until real invoicing needs it.
ALTER TABLE tenants ADD COLUMN tax_registration_id uuid REFERENCES tax_registrations(id);

-- Invoice series is facility-scoped for numbering even though it's owned by a tax registration
-- (a chain's two facilities on the same registration still number invoices independently) -- carries
-- its own tenant_id + RLS, same pattern as every other facility-scoped table in this schema.
CREATE TABLE invoice_series (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  tax_registration_id uuid NOT NULL REFERENCES tax_registrations(id),
  prefix              text NOT NULL,
  fiscal_year         text NOT NULL,
  next_number         bigint NOT NULL DEFAULT 1,
  kind                text NOT NULL DEFAULT 'invoice' CHECK (kind IN ('invoice', 'credit_note', 'receipt')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, kind, fiscal_year)
);
CREATE TRIGGER trg_invoice_series_updated_at BEFORE UPDATE ON invoice_series
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE invoice_series ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_series
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
