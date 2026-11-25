SET lock_timeout = '5s';
-- PLT-047: data-subject requests (access/export, correction, erasure). Intake from WhatsApp (Patient Access Edge), the desk and,
-- later, patient-web; the owner's privacy queue with due dates; the access/export job and its delivery through an IDC-015 secure link.

-- Statutory periods per market, in days from receipt. Reference data (no tenant id), read through tenants.region_code.
-- NEEDS LEGAL CHECK: 30 days is a placeholder for every market and type until the DPO confirms the periods (spec risk note).
CREATE TABLE dsr_market_periods (
  region_code     char(2) PRIMARY KEY REFERENCES regions(code),
  access_days     int NOT NULL CHECK (access_days > 0),
  correction_days int NOT NULL CHECK (correction_days > 0),
  erasure_days    int NOT NULL CHECK (erasure_days > 0)
);
INSERT INTO dsr_market_periods (region_code, access_days, correction_days, erasure_days) SELECT code, 30, 30, 30 FROM regions;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON dsr_market_periods FROM nabd_app;
    GRANT SELECT ON dsr_market_periods TO nabd_app;
  END IF;
END $$;

-- requester_patient_id is who asked and who receives the link: the patient, or the guardian of a minor (requester_type caregiver).
-- export_file_id names the encrypted package in object storage (tenant/{tenant}/dsr/{export_file_id}.zip.enc), not a file_objects
-- row: the package must not be reachable through the generic file paths. export_started_at is the worker's claim.
CREATE TABLE dsr_requests (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  patient_id           uuid NOT NULL,
  requester_type       text NOT NULL CHECK (requester_type IN ('patient', 'caregiver', 'desk')),
  requester_patient_id uuid NOT NULL,
  type                 text NOT NULL CHECK (type IN ('access', 'correction', 'erasure')),
  channel              text NOT NULL CHECK (channel IN ('whatsapp', 'web', 'desk')),
  status               text NOT NULL DEFAULT 'received'
                       CHECK (status IN ('received', 'verifying', 'in_progress', 'awaiting_notice', 'completed', 'rejected', 'extended')),
  details              text CHECK (char_length(details) <= 2000),
  received_at          timestamptz NOT NULL DEFAULT now(),
  due_at               timestamptz NOT NULL,
  completed_at         timestamptz,
  export_file_id       uuid,
  export_link_id       uuid,
  export_started_at    timestamptz,
  export_attempts      int NOT NULL DEFAULT 0,
  reminded_at          timestamptz,
  closure_reason       text,
  created_by           uuid,
  CONSTRAINT dsr_requests_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT dsr_requests_requester_fk FOREIGN KEY (tenant_id, requester_patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT dsr_requests_due_after_receipt CHECK (due_at > received_at)
);
CREATE INDEX idx_dsr_requests_queue ON dsr_requests (tenant_id, status, due_at);
-- one open request per patient and type (409 OPEN_REQUEST_EXISTS)
CREATE UNIQUE INDEX uq_dsr_requests_open ON dsr_requests (tenant_id, patient_id, type) WHERE status NOT IN ('completed', 'rejected');
SELECT enforce_tenant_rls('dsr_requests');

-- Every step of a request, append-only: a row is never changed. Rows leave only with their request (tenant erasure, demo purge).
CREATE TABLE dsr_request_events (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  request_id uuid NOT NULL REFERENCES dsr_requests(id),
  at         timestamptz NOT NULL DEFAULT now(),
  actor_type text NOT NULL,
  actor_id   uuid,
  event      text NOT NULL,
  note       text
);
CREATE INDEX idx_dsr_request_events_request ON dsr_request_events (tenant_id, request_id, id);
SELECT enforce_tenant_rls('dsr_request_events');
CREATE FUNCTION dsr_request_events_no_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'dsr_request_events is append-only: UPDATE is not permitted' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_dsr_request_events_no_update BEFORE UPDATE ON dsr_request_events
  FOR EACH ROW EXECUTE FUNCTION dsr_request_events_no_update();

-- The export package is delivered through an IDC-015 secure link whose content is the package itself (document_id = the request).
ALTER TABLE secure_links DROP CONSTRAINT secure_links_document_type_check;
ALTER TABLE secure_links ADD CONSTRAINT secure_links_document_type_check
  CHECK (document_type IN ('rx', 'invoice', 'report', 'document', 'package_card', 'data_export')) NOT VALID;
ALTER TABLE secure_links VALIDATE CONSTRAINT secure_links_document_type_check;

-- The Nabd DPO queue (operator console): open requests that were reminded (75% of the period gone) or are overdue, across clinics.
-- Ids, type, status and dates only: no patient id, no details. Same precedent as claim_due_plan_changes (V3061).
CREATE FUNCTION dsr_dpo_queue(p_now timestamptz)
RETURNS TABLE (request_id uuid, tenant_id uuid, tenant_name text, type text, status text, received_at timestamptz, due_at timestamptz,
               reminded_at timestamptz, overdue boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT r.id, r.tenant_id, t.name, r.type, r.status, r.received_at, r.due_at, r.reminded_at, r.due_at <= p_now
    FROM dsr_requests r JOIN tenants t ON t.id = r.tenant_id
   WHERE r.status NOT IN ('completed', 'rejected') AND (r.reminded_at IS NOT NULL OR r.due_at <= p_now)
   ORDER BY r.due_at, r.id LIMIT 500
$$;
REVOKE ALL ON FUNCTION dsr_dpo_queue(timestamptz) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION dsr_dpo_queue(timestamptz) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
