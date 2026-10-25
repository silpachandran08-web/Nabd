-- FIN-009: India GST rules. Charge head tax fields, the clinic's registration as seen by billing, and the seed rules.
-- Every seeded rate/code is a starting point only: Needs external verification (CA worked examples, FIN-010).
SET lock_timeout = '5s';

ALTER TABLE charge_catalogue
  ADD COLUMN IF NOT EXISTS tax_category text,
  ADD COLUMN IF NOT EXISTS sac_hsn text,
  ADD COLUMN IF NOT EXISTS tax_inclusive boolean NOT NULL DEFAULT false;
ALTER TABLE charge_catalogue ADD CONSTRAINT chk_charge_tax_category
  CHECK (tax_category IS NULL OR tax_category IN ('exempt', 'nil', 'zero_rated', 'taxable', 'out_of_scope')) NOT VALID;
ALTER TABLE charge_catalogue VALIDATE CONSTRAINT chk_charge_tax_category;

ALTER TABLE invoices ADD CONSTRAINT chk_invoice_document_type CHECK (document_type IN ('invoice', 'bill_of_supply')) NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT chk_invoice_document_type;

-- The API role cannot read tax_registrations (V212). This returns only the calling tenant's own GSTIN and registered flag
-- (tenant taken from the session, never a parameter); no row = the clinic has no registration on file.
CREATE OR REPLACE FUNCTION billing_clinic_tax_profile()
RETURNS TABLE (gstin text, registered boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT r.gstin_or_vat_no, r.registered
    FROM tenants t JOIN tax_registrations r ON r.id = t.tax_registration_id
   WHERE t.id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
$$;
SELECT nabd_adopt_definer_functions();

INSERT INTO tax_rules (jurisdiction, category, tax_category, rate, effective_from, source_note) VALUES
  ('IN', '9993',    'exempt',  0.00,  DATE '2017-07-01', 'Health care services by a clinical establishment, SAC 9993 (FIN-009 story). Needs external verification (CA, FIN-010).'),
  ('IN', '3004',    'taxable', 5.00,  DATE '2025-09-22', 'Medicaments, HSN 3004; 5% after the Sep 2025 rationalisation per FIN-009 context. Needs external verification (CA, FIN-010).'),
  ('IN', '3003',    'taxable', 5.00,  DATE '2025-09-22', 'Medicaments, HSN 3003; 5% after the Sep 2025 rationalisation per FIN-009 context. Needs external verification (CA, FIN-010).'),
  ('IN', 'SLAB:0',  'nil',     0.00,  DATE '2017-07-01', 'Allowed-rate marker. Needs external verification (CA, FIN-010).'),
  ('IN', 'SLAB:5',  'taxable', 5.00,  DATE '2017-07-01', 'Allowed-rate marker. Needs external verification (CA, FIN-010).'),
  ('IN', 'SLAB:12', 'taxable', 12.00, DATE '2017-07-01', 'Allowed-rate marker (FIN-009 lists 0/5/12/18). Needs external verification (CA, FIN-010).'),
  ('IN', 'SLAB:18', 'taxable', 18.00, DATE '2017-07-01', 'Allowed-rate marker; cosmetic and aesthetic services (FIN-009 story). Needs external verification (CA, FIN-010).');
