SET lock_timeout = '5s';
-- CLN-032: medicines the patient says they are taking that this clinic did not prescribe (another clinic, over the counter).
-- Read by the active-medicines resolver next to the lines of signed prescriptions; drug_id (a drug master id) is optional,
-- and only a coded row can take part in the interaction and duplicate-therapy checks. A row is current from started_on
-- (unknown = already) until stopped_on (unknown = still taking it).
CREATE TABLE patient_reported_medicines (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  patient_id  uuid NOT NULL,
  drug_id     text, -- a drug master id; no FK, like prescription_items.drug_id (a retired or unknown id simply is not checked)
  text        text NOT NULL CHECK (btrim(text) <> '' AND length(text) <= 200),
  started_on  date,
  stopped_on  date,
  recorded_by uuid NOT NULL REFERENCES staff(id),
  recorded_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT patient_reported_medicines_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT patient_reported_medicines_dates CHECK (started_on IS NULL OR stopped_on IS NULL OR stopped_on >= started_on)
);
CREATE INDEX idx_patient_reported_medicines_patient ON patient_reported_medicines (tenant_id, patient_id);
SELECT enforce_tenant_rls('patient_reported_medicines');
