SET lock_timeout = '5s';
-- CLN-050 (DENT-01/02/03): clinic tooth-numbering preference, X-ray/photo library and chair-side preset notes.
-- Tooth numbers are always stored as FDI (11-48): Universal numbering is a display choice only, so switching loses nothing.

-- One row per clinic. presets NULL = the built-in defaults (the spec's examples, pending clinical review).
CREATE TABLE dental_settings (
  tenant_id  uuid PRIMARY KEY REFERENCES tenants (id),
  numbering  text NOT NULL DEFAULT 'FDI' CHECK (numbering IN ('FDI', 'UNIVERSAL')),
  presets    jsonb CHECK (presets IS NULL OR jsonb_typeof(presets) = 'array'),
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('dental_settings');

-- A dentist's own presets: merged over the clinic list by code (same code replaces, a new code adds).
CREATE TABLE dental_preset_overrides (
  tenant_id  uuid NOT NULL REFERENCES tenants (id),
  staff_id   uuid NOT NULL,
  presets    jsonb NOT NULL CHECK (jsonb_typeof(presets) = 'array'),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, staff_id),
  CONSTRAINT dental_preset_overrides_staff_fk FOREIGN KEY (tenant_id, staff_id) REFERENCES staff (tenant_id, id)
);
SELECT enforce_tenant_rls('dental_preset_overrides');

-- An uploaded (scanned, clean) image linked to teeth, or to the whole case when teeth is empty (e.g. an OPG).
CREATE TABLE dental_images (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  patient_id     uuid NOT NULL,
  queue_entry_id uuid NOT NULL,
  upload_id      uuid NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('iopa', 'bitewing', 'opg', 'intraoral_photo')),
  teeth          int[] NOT NULL DEFAULT '{}' CHECK (cardinality(teeth) <= 32),
  created_by     uuid NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dental_images_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT dental_images_upload_uq UNIQUE (tenant_id, upload_id)
);
CREATE INDEX idx_dental_images_patient ON dental_images (tenant_id, patient_id, created_at DESC);
CREATE INDEX idx_dental_images_teeth ON dental_images USING gin (teeth);
SELECT enforce_tenant_rls('dental_images');

-- A preset phrase saved against tooth + visit. la_drug_id (pharmacy item) and la_lot are set together, for 'LA given'.
CREATE TABLE dental_chair_notes (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  patient_id     uuid NOT NULL,
  queue_entry_id uuid NOT NULL,
  tooth          int NOT NULL CHECK (tooth BETWEEN 11 AND 48),
  preset_code    text NOT NULL CHECK (preset_code ~ '^[A-Z0-9_]{1,40}$'),
  text           text NOT NULL,
  la_drug_id     uuid,
  la_lot         text,
  created_by     uuid NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dental_chair_notes_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT dental_chair_notes_la_pair CHECK ((la_drug_id IS NULL) = (la_lot IS NULL))
);
CREATE INDEX idx_dental_chair_notes_patient ON dental_chair_notes (tenant_id, patient_id, tooth, created_at DESC);
SELECT enforce_tenant_rls('dental_chair_notes');
