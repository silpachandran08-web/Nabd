-- Patients module core (NB-059 registration, NB-060 duplicate detection,
-- NB-061 search, NB-062 Patient 360).
--
-- NB-041 (row-level "own patients only" doctor scoping) is deliberately NOT
-- enforced here: it depends on knowing which patients a doctor has actually
-- treated, which lives in the Encounters table from the Clinical Workspace
-- epic (E09) — not built yet. Faking it off some other column (e.g. who
-- registered the patient) would be semantically wrong, not just incomplete,
-- so every role holding patients:view currently sees every tenant patient.
-- Tighten the query in PatientRepository the moment encounters exist.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SEQUENCE patients_mrn_seq;

CREATE TABLE patients (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  mrn               text NOT NULL DEFAULT ('MRN-' || lpad(nextval('patients_mrn_seq')::text, 6, '0')),
  name              text NOT NULL,
  phone             text NOT NULL,
  dob               date NOT NULL,
  gender            text NOT NULL CHECK (gender IN ('male','female','other')),
  guardian_id       uuid REFERENCES patients(id),
  address           text,
  national_id_enc   bytea,     -- Aadhaar/Iqama, AES-GCM encrypted (NB-140 pattern); never returned via API
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active','merged')),
  merged_into_id    uuid REFERENCES patients(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, mrn),
  CHECK ((status = 'merged') = (merged_into_id IS NOT NULL))
);
CREATE TRIGGER trg_patients_updated_at BEFORE UPDATE ON patients
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE patients ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patients
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- search (NB-061): phone/MRN prefix match, trigram name match — trigram is
-- character-based so it works the same for Arabic/Devanagari/Latin script
-- without any per-language tokenisation.
CREATE INDEX idx_patients_phone ON patients (tenant_id, phone);
CREATE INDEX idx_patients_mrn ON patients (tenant_id, mrn);
CREATE INDEX idx_patients_name_trgm ON patients USING gin (name gin_trgm_ops);

-- registration listing / cursor pagination
CREATE INDEX idx_patients_tenant_created ON patients (tenant_id, created_at, id);

-- duplicate detection (NB-060): phone-exact is the strong signal, trigram + DOB the fuzzy one
CREATE INDEX idx_patients_dob ON patients (tenant_id, dob);
