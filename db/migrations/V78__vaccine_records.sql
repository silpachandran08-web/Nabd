-- NUR-B2: record-only vaccine log. This is a factual, append-only record of a vaccine a nurse gave
-- (name / dose / route / site / lot as entered). It deliberately carries NO schedule, NO due-date
-- and NO series logic: no immunisation schedule reference data exists in the product, so a "due
-- list" is not derivable and is not built. Vaccine name is free text typed by the nurse.
CREATE TABLE vaccine_records (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  patient_id     uuid NOT NULL REFERENCES patients(id),
  queue_entry_id uuid REFERENCES queue_entries(id),
  recorded_by    uuid NOT NULL REFERENCES staff(id),
  vaccine_name   text NOT NULL CHECK (length(btrim(vaccine_name)) > 0),
  dose           text NOT NULL CHECK (length(btrim(dose)) > 0),
  route          text NOT NULL CHECK (route IN ('IM', 'IV', 'SC', 'infusion', 'oral', 'topical')),
  site           text,
  lot_number     text,
  expiry_date    date,
  notes          text,
  administered_at timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE vaccine_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vaccine_records USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_vaccine_records_patient ON vaccine_records (patient_id, administered_at DESC);
CREATE INDEX idx_vaccine_records_tenant_day ON vaccine_records (tenant_id, administered_at DESC);
