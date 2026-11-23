SET lock_timeout = '5s';
-- FIN-036: shared insurance model (module insurance) and the payer / patient split on an invoice (module billing).
-- insurance_payers and insurance_plans are mixed tables: tenant_id NULL is the global master (reference data, shipped by a later
-- migration per market: none is seeded here), a tenant row is the clinic's own. RLS null_arm 'read' (as consent_templates, V3140):
-- every clinic reads the global rows, nobody writes them through the API role.
CREATE TABLE insurance_payers (
  id           uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id    uuid REFERENCES tenants (id),
  name         text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 120),
  kind         text NOT NULL CHECK (kind IN ('insurer', 'tpa', 'government_scheme')),
  jurisdiction char(2) NOT NULL REFERENCES regions (code),
  -- per-market identifiers the claims adapters address the payer by (FIN-037.., FIN-053..); a new market adds its key here
  identifiers  jsonb NOT NULL DEFAULT '{}'
               CHECK (jsonb_typeof(identifiers) = 'object'
                      AND identifiers - ARRAY['irdai', 'dha_payer_id', 'nphies_id', 'shafafiya_id'] = '{}'::jsonb),
  active       boolean NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_insurance_payers_tenant_name ON insurance_payers (tenant_id, lower(name)) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_insurance_payers_jurisdiction ON insurance_payers (jurisdiction, lower(name));
SELECT enforce_tenant_rls('insurance_payers', 'read');

-- A plan carries the co-pay rule: the share of every bill line the patient pays (0 = the payer pays all, 100 = the patient).
CREATE TABLE insurance_plans (
  id            uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id     uuid REFERENCES tenants (id),
  payer_id      uuid NOT NULL REFERENCES insurance_payers (id),
  name          text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 120),
  copay_percent numeric(5,2) NOT NULL CHECK (copay_percent BETWEEN 0 AND 100),
  active        boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_insurance_plans_tenant_name ON insurance_plans (tenant_id, payer_id, lower(name)) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_insurance_plans_payer ON insurance_plans (payer_id);
SELECT enforce_tenant_rls('insurance_plans', 'read');

-- member_id and policy_number are encrypted in the application (FieldCipher, AAD tenant:patient_policies:<column>:id).
CREATE TABLE patient_policies (
  id                uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id         uuid NOT NULL REFERENCES tenants (id),
  patient_id        uuid NOT NULL REFERENCES patients (id),
  payer_id          uuid NOT NULL REFERENCES insurance_payers (id),
  plan_id           uuid REFERENCES insurance_plans (id),
  member_id_enc     bytea NOT NULL,
  policy_number_enc bytea NOT NULL,
  valid_from        date NOT NULL,
  valid_to          date NOT NULL,
  relationship      text NOT NULL CHECK (relationship IN ('self', 'spouse', 'child', 'parent', 'other')),
  card_document_ids uuid[] NOT NULL DEFAULT '{}',
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
  created_by        uuid NOT NULL REFERENCES staff (id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  CHECK (valid_to >= valid_from)
);
CREATE INDEX idx_patient_policies_patient ON patient_policies (tenant_id, patient_id);
SELECT enforce_tenant_rls('patient_policies');

-- One coverage decision per visit (queue entry). copay_rule is the plan's rule as it stood when the visit was marked insured.
CREATE TABLE visit_coverages (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  visit_id        uuid NOT NULL UNIQUE REFERENCES queue_entries (id),
  mode            text NOT NULL CHECK (mode IN ('self_pay', 'insured')),
  policy_id       uuid REFERENCES patient_policies (id),
  eligibility_ref text CHECK (length(eligibility_ref) <= 80),
  copay_rule      jsonb,
  updated_by      uuid NOT NULL REFERENCES staff (id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK ((mode = 'insured') = (policy_id IS NOT NULL AND copay_rule IS NOT NULL))
);
CREATE INDEX idx_visit_coverages_tenant ON visit_coverages (tenant_id);
SELECT enforce_tenant_rls('visit_coverages');

-- Billing: the split. payer_share is what the insurer owes of the invoice total; the patient's balance is total - payer_share - paid.
-- An uninsured invoice keeps payer_share 0 and NULL line shares, so every figure it had before is unchanged.
ALTER TABLE invoice_line_items ADD COLUMN IF NOT EXISTS patient_share_minor bigint,
  ADD COLUMN IF NOT EXISTS payer_share_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS payer_id uuid,
  ADD COLUMN IF NOT EXISTS payer_share numeric(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS payer_share_minor bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS patient_share_minor bigint,
  ADD COLUMN IF NOT EXISTS payer_paid numeric(12,2) NOT NULL DEFAULT 0,
  -- set when the payer has paid less than its share: the difference waits for a decision (FIN-044), it is never the patient's
  ADD COLUMN IF NOT EXISTS payer_shortfall_status text;
ALTER TABLE invoices ADD CONSTRAINT invoices_payer_shortfall_status_check
  CHECK (payer_shortfall_status IS NULL OR payer_shortfall_status = 'pending_decision') NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_payer_shortfall_status_check;
-- payer_share <= total is kept by billing (PayerSplitService), not here: a later billing stop rewrites total, then the split
ALTER TABLE invoices ADD CONSTRAINT invoices_payer_share_bounds
  CHECK (payer_share >= 0 AND payer_paid >= 0 AND payer_paid <= payer_share) NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_payer_share_bounds;

-- The FIN-006 trigger keeps the minor-unit columns in step (same body as V1921 plus the two shares).
CREATE OR REPLACE FUNCTION money_sync_invoices() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.subtotal_minor := to_minor(NEW.subtotal, NEW.currency);
  NEW.discount_minor := to_minor(NEW.discount, NEW.currency);
  NEW.tax_minor := to_minor(NEW.tax, NEW.currency);
  NEW.round_off_minor := to_minor(NEW.round_off, NEW.currency);
  NEW.total_minor := to_minor(NEW.total, NEW.currency);
  NEW.paid_minor := to_minor(NEW.paid, NEW.currency);
  NEW.payer_share_minor := to_minor(NEW.payer_share, NEW.currency);
  NEW.patient_share_minor := NEW.total_minor - NEW.payer_share_minor;
  RETURN NEW;
END $$;

-- What a payer actually paid against its share (a remittance), one row per payment. Kept apart from invoice_payments: that
-- table is the desk's cash and feeds day close; invoices.payer_paid is always the sum of these rows.
CREATE TABLE invoice_payer_payments (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  invoice_id      uuid NOT NULL REFERENCES invoices (id),
  payer_id        uuid NOT NULL,
  method          text NOT NULL DEFAULT 'insurance' CHECK (method = 'insurance'),
  amount          numeric(12,2) NOT NULL CHECK (amount > 0),
  reference       text CHECK (length(reference) <= 100),
  recorded_by     uuid NOT NULL REFERENCES staff (id),
  recorded_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoice_payer_payments_invoice ON invoice_payer_payments (tenant_id, invoice_id);
SELECT enforce_tenant_rls('invoice_payer_payments');

-- The built-in Owner role cannot be edited through the API, so an existing clinic's Owner would never reach insurance: give it
-- the insurance module (as V3540 did for lab), one audit row per changed role. Other roles are granted in the role editor.
DO $$
DECLARE
  t record;
  r record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    FOR r IN
      UPDATE roles ro
         SET grants = ro.grants || '[{"module":"insurance","view":true,"create":true,"edit":true,"delete":true,"approve":true,"refundDiscount":true,"export":true,"breakGlass":false}]'::jsonb
       WHERE ro.tenant_id = t.id AND ro.built_in
         AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(ro.grants) g WHERE g->>'module' = 'insurance')
      RETURNING ro.id
    LOOP
      INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, before, after)
      VALUES (t.id, 'system', NULL, 'System', 'System', 'role.owner_insurance_grant_added', 'role', r.id,
              '{"insurance": null}'::jsonb, '{"insurance": "all", "source": "FIN-036"}'::jsonb);
    END LOOP;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
