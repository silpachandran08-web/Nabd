SET lock_timeout = '5s';
-- migration-lint: allow MIG-001 partial index on the new nullable column (no row matches yet); Flyway runs in a transaction
-- PHL-020: an outside (paper or WhatsApp-photo) prescription recorded at the counter so a prescription-only line can be
-- billed against it. The photo is a file of the PLT-029 pipeline (purpose paper_rx: type check, antivirus, tenant storage);
-- this row holds the prescriber, the date and the link. How much of a line is used is NOT stored here: it is the sum of the
-- non-void invoice lines that point at it (same rule as PHL-001), so a voided sale gives the quantity back by itself.
CREATE TABLE paper_prescriptions (
  id                      uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id               uuid NOT NULL REFERENCES tenants (id),
  prescriber_name         text NOT NULL CHECK (length(btrim(prescriber_name)) BETWEEN 1 AND 120),
  prescriber_reg_no       text NOT NULL CHECK (length(btrim(prescriber_reg_no)) BETWEEN 1 AND 60),
  rx_date                 date NOT NULL,
  customer_name           text CHECK (length(customer_name) <= 120),
  customer_phone          text CHECK (length(customer_phone) <= 20),
  document_id             uuid UNIQUE REFERENCES file_objects (id),   -- one photo backs one record
  duplicate_copy_retained boolean NOT NULL DEFAULT false,
  valid_until             date,                                       -- rx_date + the shortest schedule-class validity; NULL = no rule
  created_by              uuid NOT NULL REFERENCES staff (id),
  created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_paper_prescriptions_recent ON paper_prescriptions (tenant_id, created_at DESC);
SELECT enforce_tenant_rls('paper_prescriptions');

CREATE TABLE paper_prescription_lines (
  id          uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id   uuid NOT NULL REFERENCES tenants (id),
  paper_rx_id uuid NOT NULL REFERENCES paper_prescriptions (id),
  item_id     uuid REFERENCES charge_catalogue (id),
  drug_text   text CHECK (length(drug_text) <= 200),
  qty         int NOT NULL CHECK (qty > 0),
  CHECK (item_id IS NOT NULL OR length(btrim(drug_text)) > 0)
);
CREATE INDEX idx_paper_prescription_lines_rx ON paper_prescription_lines (tenant_id, paper_rx_id);
SELECT enforce_tenant_rls('paper_prescription_lines');

-- The photo's own purpose: FileService hides paper_rx files from every generic path (info, bind), so only the pharmacy
-- endpoint can hand out a URL for one.
ALTER TABLE file_objects DROP CONSTRAINT file_objects_purpose_check;
ALTER TABLE file_objects ADD CONSTRAINT file_objects_purpose_check
  CHECK (purpose IN ('outside_report', 'clinical_photo', 'xray', 'consent_form', 'claim_pack', 'signature', 'prescription', 'paper_rx')) NOT VALID;
ALTER TABLE file_objects VALIDATE CONSTRAINT file_objects_purpose_check;

-- The PHL-001 rule now accepts either link. Same constraint name; validated only when no historical row breaks it (V1350).
ALTER TABLE invoice_line_items ADD COLUMN paper_rx_line_id uuid REFERENCES paper_prescription_lines (id);
ALTER TABLE invoice_line_items DROP CONSTRAINT invoice_line_items_rx_linked;
ALTER TABLE invoice_line_items ADD CONSTRAINT invoice_line_items_rx_linked
  CHECK (NOT is_rx OR prescription_item_id IS NOT NULL OR paper_rx_line_id IS NOT NULL) NOT VALID;
DO $$
BEGIN
  ALTER TABLE invoice_line_items VALIDATE CONSTRAINT invoice_line_items_rx_linked;
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PHL-020: historical unlinked Rx lines exist; invoice_line_items_rx_linked left NOT VALID for CSO review';
END $$;
CREATE INDEX idx_invoice_line_items_paper_rx
  ON invoice_line_items (tenant_id, paper_rx_line_id) WHERE paper_rx_line_id IS NOT NULL;
