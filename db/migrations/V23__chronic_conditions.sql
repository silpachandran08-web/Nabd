-- NB-077: chronic condition & problem list. Mirrors patient_allergies exactly — a patient-level
-- (not visit-scoped) register, "carried forward between encounters" simply by being one.
CREATE TABLE chronic_conditions (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  patient_id       uuid NOT NULL REFERENCES patients(id),
  condition        text NOT NULL,
  status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'resolved')),
  review_due_date  date,
  recorded_by      uuid NOT NULL REFERENCES staff(id),
  recorded_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chronic_conditions_patient ON chronic_conditions (tenant_id, patient_id) WHERE status = 'active';
-- The "due list" is derived from this index, not a manually maintained flag (NB-077's own acceptance bar).
CREATE INDEX idx_chronic_conditions_due ON chronic_conditions (tenant_id, review_due_date) WHERE status = 'active';
ALTER TABLE chronic_conditions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chronic_conditions USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
