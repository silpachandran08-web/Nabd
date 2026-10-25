-- ACC-038: opaque per-tenant patient references for the Patient Access Edge. The Edge never sees a patient id: it holds a
-- random token that only this table resolves, and only inside the tenant named in the /internal/v1/tenants/{tid} path.
SET lock_timeout = '5s';

CREATE TABLE internal_patient_refs (
  tenant_id   uuid        NOT NULL REFERENCES tenants(id),
  patient_ref text        NOT NULL,
  patient_id  uuid        NOT NULL REFERENCES patients(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, patient_ref),
  UNIQUE (tenant_id, patient_id)
);
SELECT enforce_tenant_rls('internal_patient_refs');

-- Household lookup by the SHA-256 (hex) of a verified phone's E.164 digits; every expression here is immutable.
-- migration-lint: allow MIG-001 patients is large in production; run in the maintenance window (same as the V1930 search columns)
CREATE INDEX idx_patients_phone_sha256 ON patients (tenant_id, (encode(sha256(decode(phone_digits, 'escape')), 'hex')));
