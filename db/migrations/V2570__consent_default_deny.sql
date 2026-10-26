SET lock_timeout = '5s';
-- IDC-004/006/007: processing is default-deny (no consent row = not processable) for every clinic created from now on.
-- Clinics that exist at migration time are demo / pilot-shadow data (no real patient data before the S2 gate): they stay on
-- the legacy rule (only a withdrawal blocks) until the owner switches them: UPDATE tenants SET consent_default_deny = true.
ALTER TABLE tenants
  ADD COLUMN consent_default_deny boolean NOT NULL DEFAULT true,
  ADD COLUMN consent_two_witnesses boolean NOT NULL DEFAULT false;   -- IDC-007: assisted consent needs a second witness
UPDATE tenants SET consent_default_deny = false;

-- IDC-004: a minor's consent is given by the linked guardian; the row names that guardian (patient id) next to the notice version.
ALTER TABLE consents ADD COLUMN guardian_patient_id uuid REFERENCES patients(id);

-- the default-deny gate asks for the newest row of one purpose per patient on every read
-- migration-lint: allow MIG-001 consents is small and the migrator runs before the API serves traffic
CREATE INDEX idx_consents_patient_purpose_newest ON consents (tenant_id, patient_id, consent_type, created_at DESC);
