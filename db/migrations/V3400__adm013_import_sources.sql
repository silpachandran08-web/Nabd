SET lock_timeout = '5s';
-- ADM-013: imports from old clinic software (source adapters), visit history, drug list and opening stock.

-- 1. Job: more import types and the source the owner picked (csv = generic file, headings matched by synonyms).
ALTER TABLE data_import_jobs ADD COLUMN source text;
ALTER TABLE data_import_jobs DROP CONSTRAINT data_import_jobs_import_type_check;
ALTER TABLE data_import_jobs ADD CONSTRAINT data_import_jobs_import_type_check
  CHECK (import_type IN ('patients','appointments','invoices','charges','visit_history','drugs','stock')) NOT VALID;
ALTER TABLE data_import_jobs VALIDATE CONSTRAINT data_import_jobs_import_type_check;
ALTER TABLE data_import_jobs ADD CONSTRAINT data_import_jobs_source_check
  CHECK (source IS NULL OR source IN ('practo_ray','clinicea','lybrate','medas','csv')) NOT VALID;
ALTER TABLE data_import_jobs VALIDATE CONSTRAINT data_import_jobs_source_check;

-- 2. Upload: an export zip (a set of CSV / XLSX files) next to a single file.
ALTER TABLE data_import_uploads DROP CONSTRAINT data_import_uploads_source_format_check;
ALTER TABLE data_import_uploads ADD CONSTRAINT data_import_uploads_source_format_check
  CHECK (source_format IN ('xlsx','csv','zip')) NOT VALID;
ALTER TABLE data_import_uploads VALIDATE CONSTRAINT data_import_uploads_source_format_check;

-- 3. A past visit from the old software is one issued clinical document of type 'imported_history': it has no visit
--    (queue entry) and no staff doctor here, the doctor's name is text in the body. Issued documents are already final
--    (V54 triggers), so imported history is read-only at the database level. No invoice and no series number is involved.
ALTER TABLE clinical_documents DROP CONSTRAINT clinical_documents_doc_type_check;
ALTER TABLE clinical_documents ADD CONSTRAINT clinical_documents_doc_type_check
  CHECK (doc_type IN ('visit_summary','referral','certificate','imported_history')) NOT VALID;
ALTER TABLE clinical_documents VALIDATE CONSTRAINT clinical_documents_doc_type_check;
ALTER TABLE clinical_documents ALTER COLUMN queue_entry_id DROP NOT NULL;
ALTER TABLE clinical_documents ALTER COLUMN doctor_id DROP NOT NULL;
ALTER TABLE clinical_documents ADD CONSTRAINT clinical_documents_visit_or_imported
  CHECK (doc_type = 'imported_history' OR (queue_entry_id IS NOT NULL AND doctor_id IS NOT NULL)) NOT VALID;
ALTER TABLE clinical_documents VALIDATE CONSTRAINT clinical_documents_visit_or_imported;
