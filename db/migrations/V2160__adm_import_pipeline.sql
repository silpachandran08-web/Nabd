SET lock_timeout = '5s';
-- ADM-012: staged patient import. Uploaded file -> encrypted staged rows -> dry-run diff -> chunked commit.

-- 1. Job columns. Statuses 'dry_run' (diff ready for the owner) and 'awaiting_commit' are added next to the originals.
ALTER TABLE data_import_jobs
  ADD COLUMN upload_id    uuid,
  ADD COLUMN mapping      jsonb,
  ADD COLUMN diff_summary jsonb,
  ADD COLUMN rows_total   int,
  ADD COLUMN rows_done    int NOT NULL DEFAULT 0,
  ADD COLUMN last_chunk   int NOT NULL DEFAULT 0;
ALTER TABLE data_import_jobs DROP CONSTRAINT data_import_jobs_status_check;
ALTER TABLE data_import_jobs ADD CONSTRAINT data_import_jobs_status_check
  CHECK (status IN ('pending','validating','dry_run','awaiting_commit','importing','done','failed')) NOT VALID;
ALTER TABLE data_import_jobs VALIDATE CONSTRAINT data_import_jobs_status_check;
CREATE INDEX idx_data_import_jobs_active ON data_import_jobs (status, updated_at) WHERE status IN ('validating','importing'); -- migration-lint: allow MIG-001 data_import_jobs holds a handful of rows per clinic

-- 2. The raw upload, encrypted (FieldCipher), deleted as soon as it is parsed into staged rows.
CREATE TABLE data_import_uploads (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  file_name    text NOT NULL,
  source_format text NOT NULL CHECK (source_format IN ('xlsx','csv')),
  size_bytes   int NOT NULL,
  content_enc  bytea NOT NULL,
  scan_status  text NOT NULL DEFAULT 'unscanned' CHECK (scan_status IN ('unscanned','clean')),
  created_by   uuid NOT NULL REFERENCES staff(id),
  created_at   timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('data_import_uploads');

-- 3. Staged rows. payload_enc is the raw cells (PHI) encrypted; purged 30 days after the job ends.
CREATE TABLE data_import_rows (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  job_id      uuid NOT NULL REFERENCES data_import_jobs(id) ON DELETE CASCADE,
  row_no      int NOT NULL,
  payload_enc bytea NOT NULL,
  outcome     text NOT NULL DEFAULT 'staged' CHECK (outcome IN ('staged','ready','duplicate','error','created','merged','skipped')),
  decision    text CHECK (decision IN ('skip','merge','create')),
  error_code  text,
  patient_id  uuid,
  UNIQUE (job_id, row_no)
);
CREATE INDEX idx_data_import_rows_outcome ON data_import_rows (tenant_id, job_id, outcome);
SELECT enforce_tenant_rls('data_import_rows');

-- 4. Old record number on the patient; imported allergies wait for a doctor.
ALTER TABLE patients ADD COLUMN legacy_record_no text;
ALTER TABLE patient_allergies ADD COLUMN unconfirmed boolean NOT NULL DEFAULT false;
