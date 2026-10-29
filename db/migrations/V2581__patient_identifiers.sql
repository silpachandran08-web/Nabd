SET lock_timeout = '5s';
-- IDC-017: government / national health identifiers of a patient (ABHA first). The value is AES-GCM sealed (FieldCipher, AAD
-- bound to the row) and looked up through a keyed blind index, never in clear. Aadhaar is NOT an id_type: it never reaches the database.
CREATE TABLE patient_identifiers (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  patient_id   uuid NOT NULL REFERENCES patients(id),
  id_type      text NOT NULL CHECK (id_type IN ('ABHA_NUMBER', 'ABHA_ADDRESS', 'EMIRATES_ID', 'SA_NATIONAL_ID', 'IQAMA', 'QID', 'PASSPORT')),
  blind_index  text NOT NULL CHECK (blind_index ~ '^[0-9a-f]{64}$'),
  value_enc    bytea NOT NULL,
  last4        text NOT NULL CHECK (length(last4) BETWEEN 1 AND 4),
  verified_via text NOT NULL CHECK (verified_via IN ('abdm_mobile_otp', 'abdm_aadhaar_otp', 'abdm_qr', 'abdm_enrol')),
  verified_at  timestamptz NOT NULL DEFAULT now(),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id_type, blind_index)
);
CREATE INDEX idx_patient_identifiers_patient ON patient_identifiers (tenant_id, patient_id);
SELECT enforce_tenant_rls('patient_identifiers');
