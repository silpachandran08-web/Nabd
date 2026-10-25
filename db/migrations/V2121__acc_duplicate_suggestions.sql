SET lock_timeout = '5s';
-- ACC-030: a dismissed duplicate suggestion. fingerprint covers the matched fields and their values, so the pair
-- comes back when a matched field changes. patient_a < patient_b (uuid order) so a pair has one row per fingerprint.
CREATE TABLE patient_duplicate_dismissals (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  patient_a    uuid NOT NULL REFERENCES patients(id),
  patient_b    uuid NOT NULL REFERENCES patients(id),
  fingerprint  text NOT NULL,
  dismissed_by uuid NOT NULL REFERENCES staff(id),
  dismissed_at timestamptz NOT NULL DEFAULT now(),
  CHECK (patient_a < patient_b),
  UNIQUE (tenant_id, patient_a, patient_b, fingerprint)
);
SELECT enforce_tenant_rls('patient_duplicate_dismissals');
