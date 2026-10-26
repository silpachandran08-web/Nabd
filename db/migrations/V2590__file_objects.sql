SET lock_timeout = '5s';
-- PLT-029: one row per uploaded file. The bytes live in the object store (quarantine key until the scan passes, then the
-- tenant key); this row is the status and the metadata. No file content or file name is stored here.
CREATE TABLE file_objects (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  purpose        text NOT NULL CHECK (purpose IN ('outside_report', 'clinical_photo', 'xray', 'consent_form')),
  storage_key    text NOT NULL,
  quarantine_key text,
  declared_type  text NOT NULL,
  detected_type  text,
  size_bytes     bigint NOT NULL CHECK (size_bytes > 0),
  sha256         bytea,
  status         text NOT NULL DEFAULT 'awaiting_upload'
                 CHECK (status IN ('awaiting_upload', 'pending_scan', 'available', 'rejected_type', 'infected', 'deleted')),
  scan_engine    text,
  scanned_at     timestamptz,
  uploaded_by    uuid REFERENCES staff(id),
  patient_id     uuid REFERENCES patients(id),
  visit_id       uuid REFERENCES queue_entries(id),
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_objects_patient ON file_objects (tenant_id, patient_id);
SELECT enforce_tenant_rls('file_objects');

-- In-app alert for the clinic owner(s) when a file is blocked (infected or wrong type).
CREATE TABLE file_blocked_alerts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  file_id         uuid NOT NULL REFERENCES file_objects(id),
  owner_staff_id  uuid NOT NULL REFERENCES staff(id),
  reason          text NOT NULL CHECK (reason IN ('infected', 'rejected_type')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (file_id, owner_staff_id)
);
SELECT enforce_tenant_rls('file_blocked_alerts');
