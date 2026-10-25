-- FIN-008: tax engine storage. tax_rules is platform reference data (no tenant_id, read-only to nabd_app, changes ship as migrations);
-- invoice_line_taxes stores each line's tax components so a reprint never recomputes.
-- Rule lookup: the rule whose category is the longest prefix of the head's SAC/HSN, effective on the clinic date.
-- Rows with a category that no SAC/HSN can match (SLAB:n) only define the set of rates an owner may pick.
SET lock_timeout = '5s';

CREATE TABLE tax_rules (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  jurisdiction   text NOT NULL,
  category       text NOT NULL,
  tax_category   text NOT NULL CHECK (tax_category IN ('exempt', 'nil', 'taxable')),
  rate           numeric(5,2) NOT NULL CHECK (rate >= 0),
  components     jsonb,
  effective_from date NOT NULL,
  effective_to   date,
  source_note    text NOT NULL,
  UNIQUE (jurisdiction, category, effective_from),
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON tax_rules FROM nabd_app;
    GRANT SELECT ON tax_rules TO nabd_app;
  END IF;
END $$;

CREATE TABLE invoice_line_taxes (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  line_id       uuid NOT NULL REFERENCES invoice_line_items(id),
  tax_type      text NOT NULL CHECK (tax_type IN ('CGST', 'SGST', 'IGST', 'UTGST', 'VAT', 'CESS')),
  rate          numeric(5,2) NOT NULL,
  taxable_minor bigint NOT NULL,
  amount_minor  bigint NOT NULL
);
CREATE INDEX idx_invoice_line_taxes_line ON invoice_line_taxes (tenant_id, line_id);
SELECT enforce_tenant_rls('invoice_line_taxes');

ALTER TABLE invoice_line_items
  ADD COLUMN IF NOT EXISTS discount_allocated_minor bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS taxable_minor bigint,
  ADD COLUMN IF NOT EXISTS tax_category text,
  ADD COLUMN IF NOT EXISTS tax_rule_id uuid REFERENCES tax_rules(id),
  ADD COLUMN IF NOT EXISTS tax_inclusive boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS inter_state boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS sac_hsn text;

-- FIN-009 needs these on the invoice: what the document is called and the B2B buyer's GSTIN.
ALTER TABLE invoices
  ADD COLUMN IF NOT EXISTS document_type text NOT NULL DEFAULT 'invoice',
  ADD COLUMN IF NOT EXISTS buyer_gstin text;
