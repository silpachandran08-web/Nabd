SET lock_timeout = '5s';
-- CLN-047: dermatology photo consent and the dated photo timeline (FRD DERM-01). Photo consent is separate from treatment
-- consent: one active row per patient (scope record_only or record_and_teaching), revocable. A withdrawal hides every photo of
-- the patient (hidden_at) but never deletes it: photos are clinical records kept under the legal hold rules. The image bytes
-- live in the PLT-029 pipeline (file_objects, scanned, metadata stripped, compressed); a photo row only points at the upload.
CREATE TABLE photo_consents (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  patient_id        uuid NOT NULL,
  scope             text NOT NULL CHECK (scope IN ('record_only', 'record_and_teaching')),
  status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'withdrawn')),
  signer_kind       text NOT NULL CHECK (signer_kind IN ('patient', 'guardian')),
  signature_file_id uuid REFERENCES file_objects(id),
  recorded_by       uuid NOT NULL REFERENCES staff(id),
  recorded_at       timestamptz NOT NULL DEFAULT now(),
  withdrawn_at      timestamptz,
  withdrawn_by      uuid REFERENCES staff(id),
  CONSTRAINT photo_consents_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT photo_consents_withdrawn CHECK ((status = 'withdrawn') = (withdrawn_at IS NOT NULL))
);
-- at most one active consent per patient: a concurrent second capture is a unique violation, answered with the first one
CREATE UNIQUE INDEX uq_photo_consents_active ON photo_consents (tenant_id, patient_id) WHERE status = 'active';
SELECT enforce_tenant_rls('photo_consents');

CREATE TABLE clinical_photos (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  patient_id     uuid NOT NULL,
  queue_entry_id uuid NOT NULL REFERENCES queue_entries(id),
  upload_id      uuid NOT NULL REFERENCES file_objects(id),
  site           jsonb NOT NULL CHECK (jsonb_typeof(site) = 'object' AND site ? 'region' AND site ? 'side'),
  note           text CHECK (length(note) <= 500),
  taken_at       timestamptz NOT NULL DEFAULT now(),
  consent_id     uuid NOT NULL REFERENCES photo_consents(id),
  hidden_at      timestamptz,
  created_by     uuid NOT NULL REFERENCES staff(id),
  CONSTRAINT clinical_photos_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT uq_clinical_photos_upload UNIQUE (tenant_id, upload_id)
);
CREATE INDEX idx_clinical_photos_patient ON clinical_photos (tenant_id, patient_id, taken_at);
SELECT enforce_tenant_rls('clinical_photos');
