-- E16 Pharmacy. Matches the wireframe's own design: pharmacy items are charge_catalogue rows
-- (category 'Pharmacy') with a stock count — selecting one in Fast Checkout bills the patient AND
-- decrements stock in the same transaction ("one invoice, one-tap stock deduction", the wireframe's
-- own words), no separate dispense screen needed.
--
-- Scope: only Hybrid mode (the wireframe's own "Phase 2, paid tier, low setup" label) is a real,
-- working feature here. In-house mode (batch & expiry, controlled-drug register, FEFO) is explicitly
-- "Phase 3" in the wireframe itself, shown there only as a preview with sample data — not built here.
ALTER TABLE charge_catalogue ADD COLUMN is_rx boolean;
ALTER TABLE charge_catalogue ADD COLUMN hsn_code text;
ALTER TABLE charge_catalogue ADD COLUMN stock_qty integer;

CREATE TABLE pharmacy_settings (
  tenant_id  uuid PRIMARY KEY REFERENCES tenants(id),
  mode       text NOT NULL DEFAULT 'external' CHECK (mode IN ('external', 'hybrid', 'in_house')),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_pharmacy_settings_updated_at BEFORE UPDATE ON pharmacy_settings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE pharmacy_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pharmacy_settings USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
);
