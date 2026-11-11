SET lock_timeout = '5s';
-- FIN-035: reimbursement claim pack for a billed visit. A format says which sections the PDF holds, in which order, which
-- consultation-summary fields it may carry (minimum necessary: never history, examination, assessment, plan or notes) and
-- which invoice-line columns go in a CSV (NULL = no CSV). tenant_id NULL = Nabd reference format; a clinic may add its own.
CREATE TABLE claim_pack_formats (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid REFERENCES tenants(id),
  code         text NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,40}$'),
  jurisdiction text CHECK (jurisdiction IN ('IN', 'SA', 'AE', 'QA')),
  insurer_name text NOT NULL,
  sections     jsonb NOT NULL CHECK (jsonb_typeof(sections) = 'array' AND jsonb_array_length(sections) > 0),
  csv_columns  jsonb CHECK (csv_columns IS NULL OR jsonb_typeof(csv_columns) = 'array'),
  active       boolean NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_claim_pack_formats_code ON claim_pack_formats (tenant_id, code) NULLS NOT DISTINCT;

-- Reference formats: generic only. Insurer-specific formats (cover-sheet fields, order, file type) need a product decision and the
-- insurers' own specifications (DECISION: FIN-035 insurer formats), so none is seeded.
INSERT INTO claim_pack_formats (tenant_id, code, jurisdiction, insurer_name, sections, csv_columns) VALUES
  (NULL, 'GENERIC', NULL, 'Generic (any insurer)',
   '[{"type":"cover"},{"type":"invoice"},{"type":"receipt"},{"type":"summary","fields":["diagnosis","procedures","medicines"]}]', NULL),
  (NULL, 'GENERIC_CSV', NULL, 'Generic with line data (CSV)',
   '[{"type":"cover"},{"type":"invoice"},{"type":"receipt"},{"type":"summary","fields":["diagnosis","procedures","medicines"]}]',
   '["invoiceNumber","invoiceDate","lineNo","description","sacHsn","quantity","unitPrice","taxRatePercent","lineTotal"]');
-- seeded before RLS is forced: a NULL-tenant row fails the own-tenant INSERT policy
SELECT enforce_tenant_rls('claim_pack_formats', 'read');

-- One row per generation (the pack is re-generated, not edited). document_id / csv_document_id are file_objects rows so the
-- IDC-015 secure link can deliver them.
CREATE TABLE claim_packs (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  visit_id        uuid NOT NULL REFERENCES queue_entries(id),
  invoice_id      uuid NOT NULL REFERENCES invoices(id),
  patient_id      uuid NOT NULL,
  format_code     text NOT NULL,
  document_id     uuid NOT NULL REFERENCES file_objects(id),
  csv_document_id uuid REFERENCES file_objects(id),
  pdf_sha256      text NOT NULL CHECK (pdf_sha256 ~ '^[0-9a-f]{64}$'),
  generated_by    uuid NOT NULL REFERENCES staff(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT claim_packs_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id)
);
CREATE INDEX idx_claim_packs_visit ON claim_packs (tenant_id, visit_id);
SELECT enforce_tenant_rls('claim_packs');

-- The pack PDF/CSV are server-rendered files (no upload, no scan) in the file store.
ALTER TABLE file_objects DROP CONSTRAINT file_objects_purpose_check;
ALTER TABLE file_objects ADD CONSTRAINT file_objects_purpose_check
  CHECK (purpose IN ('outside_report', 'clinical_photo', 'xray', 'consent_form', 'claim_pack')) NOT VALID;
ALTER TABLE file_objects VALIDATE CONSTRAINT file_objects_purpose_check;
