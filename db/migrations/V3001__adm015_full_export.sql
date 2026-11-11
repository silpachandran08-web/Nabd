SET lock_timeout = '5s';
-- ADM-015: the owner's full export. data_export_jobs (V17, a stub until now) gains the chosen datasets and format, per-dataset
-- progress, the manifest (row counts, archive SHA-256), the stored object, the download link and the step-up it was started with.
-- step_up_id is the jti of that step-up, unique: one confirmation starts one export. One export runs at a time per clinic.
ALTER TABLE data_export_jobs
  ADD COLUMN datasets     text[],
  ADD COLUMN format       text CHECK (format IN ('xlsx', 'csv', 'fhir_ndjson', 'pdf_per_patient')),
  ADD COLUMN progress     jsonb,
  ADD COLUMN manifest     jsonb,
  ADD COLUMN object_key   text,
  ADD COLUMN link_id      uuid,
  ADD COLUMN bytes        bigint,
  ADD COLUMN step_up_id   uuid,
  ADD COLUMN started_at   timestamptz,
  ADD COLUMN completed_at timestamptz;
-- migration-lint: allow MIG-001 data_export_jobs holds a few rows per clinic (one per export ever started); the index builds in milliseconds and the repo forbids CONCURRENTLY
CREATE UNIQUE INDEX uq_data_export_jobs_step_up ON data_export_jobs (step_up_id) WHERE step_up_id IS NOT NULL;
-- migration-lint: allow MIG-001 same table, same size
CREATE UNIQUE INDEX uq_data_export_jobs_running ON data_export_jobs (tenant_id) WHERE format IS NOT NULL AND status IN ('pending', 'running');
SELECT enforce_tenant_rls('data_export_jobs');

-- The download link: only the SHA-256 of the token is stored. 72 hours, 3 downloads, each download needs a fresh OTP to the
-- owner's mobile (recipient_staff_id); the OTP hash and attempt counter live here, one challenge at a time.
CREATE TABLE data_export_links (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  job_id             uuid NOT NULL REFERENCES data_export_jobs(id),
  token_hash         text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  recipient_staff_id uuid NOT NULL REFERENCES staff(id),
  expires_at         timestamptz NOT NULL,
  max_downloads      integer NOT NULL DEFAULT 3 CHECK (max_downloads > 0),
  downloads          integer NOT NULL DEFAULT 0 CHECK (downloads >= 0),
  otp_hash           text,
  otp_expires_at     timestamptz,
  otp_attempts       integer NOT NULL DEFAULT 0,
  otp_challenge_id   uuid,
  created_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT data_export_links_cap CHECK (downloads <= max_downloads)
);
CREATE INDEX idx_data_export_links_job ON data_export_links (tenant_id, job_id);
SELECT enforce_tenant_rls('data_export_links');

-- The link is opened with no session: this returns the link's clinic (nothing else) so the caller can bind it before reading
-- the row under RLS (same as proxy_invite_tenant, V2771).
CREATE FUNCTION data_export_link_tenant(p_hash text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT tenant_id FROM data_export_links WHERE token_hash = p_hash $$;
REVOKE ALL ON FUNCTION data_export_link_tenant(text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION data_export_link_tenant(text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
