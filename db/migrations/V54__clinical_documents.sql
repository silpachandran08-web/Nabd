-- V54 (DOC-030): visit outputs written by the doctor from the consult - visit summary, referral letter,
-- medical certificate. One row per document; several of a type may exist for one visit.
--
-- body   = the labelled fields the doctor entered (plain text values keyed by field name; the allowed keys per type
--          are enforced in ClinicalDocumentRules, the database only stores them).
-- header = snapshot of the clinic / doctor / patient / visit values taken when the document is ISSUED, so a printed
--          document never changes if the patient or clinic is edited later. NULL while the document is a draft.
-- An issued document is final at the database level (same approach as the signed prescription in V51): the
-- application refuses edits, this is the backstop. Expand-only, written to be safe to re-run.

CREATE TABLE IF NOT EXISTS clinical_documents (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  patient_id      uuid NOT NULL REFERENCES patients(id),
  queue_entry_id  uuid NOT NULL REFERENCES queue_entries(id),
  doctor_id       uuid NOT NULL REFERENCES staff(id),
  doc_type        text NOT NULL CHECK (doc_type IN ('visit_summary', 'referral', 'certificate')),
  status          text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'issued')),
  body            jsonb NOT NULL DEFAULT '{}'::jsonb,
  header          jsonb,
  created_by      uuid NOT NULL REFERENCES staff(id),
  issued_by       uuid REFERENCES staff(id),
  issued_at       timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'issued') = (issued_at IS NOT NULL AND issued_by IS NOT NULL AND header IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_clinical_documents_visit
  ON clinical_documents (tenant_id, queue_entry_id, created_at);
CREATE INDEX IF NOT EXISTS idx_clinical_documents_patient
  ON clinical_documents (tenant_id, patient_id, created_at DESC);

DROP TRIGGER IF EXISTS trg_clinical_documents_updated_at ON clinical_documents;
CREATE TRIGGER trg_clinical_documents_updated_at BEFORE UPDATE ON clinical_documents
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE clinical_documents ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'clinical_documents' AND policyname = 'tenant_isolation') THEN
    CREATE POLICY tenant_isolation ON clinical_documents
      USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
  END IF;
END $$;

-- An issued document can never be changed or removed.
CREATE OR REPLACE FUNCTION clinical_documents_issued_is_final() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'issued' THEN
    RAISE EXCEPTION 'clinical document is issued and cannot be % (clinical_documents)', lower(TG_OP)
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clinical_documents_issued_final ON clinical_documents;
CREATE TRIGGER trg_clinical_documents_issued_final BEFORE UPDATE ON clinical_documents
  FOR EACH ROW EXECUTE FUNCTION clinical_documents_issued_is_final();
DROP TRIGGER IF EXISTS trg_clinical_documents_issued_no_delete ON clinical_documents;
CREATE TRIGGER trg_clinical_documents_issued_no_delete BEFORE DELETE ON clinical_documents
  FOR EACH ROW EXECUTE FUNCTION clinical_documents_issued_is_final();

-- Row triggers do not fire on TRUNCATE.
CREATE OR REPLACE FUNCTION clinical_documents_no_truncate() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'TRUNCATE is not permitted on clinical_documents: issued documents are final'
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clinical_documents_no_truncate ON clinical_documents;
CREATE TRIGGER trg_clinical_documents_no_truncate BEFORE TRUNCATE ON clinical_documents
  FOR EACH STATEMENT EXECUTE FUNCTION clinical_documents_no_truncate();
