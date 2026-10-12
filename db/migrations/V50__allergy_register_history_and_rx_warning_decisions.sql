-- V50 (DOC-046 / DOC-031 / DOC-032): allergy register with category, source, reasoned corrections and
-- an append-only history; prescription warning decisions (allergy / pregnancy / controlled substance)
-- recorded per item with a named reason, who and when.
--
-- Expand-only: new nullable/defaulted columns and two new tables; nothing existing is rewritten.

-- 1. Allergy register: category + source, and who/when/why an entry was removed. A removal no longer
--    just flips `active`; the reason and actor are kept on the row (and in the history table below).
ALTER TABLE patient_allergies
  ADD COLUMN IF NOT EXISTS category            text,
  ADD COLUMN IF NOT EXISTS source              text NOT NULL DEFAULT 'clinician',
  ADD COLUMN IF NOT EXISTS updated_by          uuid REFERENCES staff(id),
  ADD COLUMN IF NOT EXISTS updated_at          timestamptz,
  ADD COLUMN IF NOT EXISTS deactivated_by      uuid REFERENCES staff(id),
  ADD COLUMN IF NOT EXISTS deactivated_at      timestamptz,
  ADD COLUMN IF NOT EXISTS deactivation_reason text;

ALTER TABLE patient_allergies
  ADD CONSTRAINT chk_patient_allergies_category CHECK (category IS NULL OR category IN ('drug', 'food', 'environmental', 'other')),
  ADD CONSTRAINT chk_patient_allergies_source   CHECK (source IN ('clinician', 'patient_reported', 'outside_record'));

-- 2. Append-only history: a correction or removal never overwrites silently. `before`/`after` hold the
--    register fields; `reason` is mandatory for everything except the first 'recorded' event.
CREATE TABLE patient_allergy_events (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  patient_id  uuid NOT NULL REFERENCES patients(id),
  allergy_id  uuid NOT NULL REFERENCES patient_allergies(id),
  event       text NOT NULL CHECK (event IN ('recorded', 'corrected', 'removed')),
  before      jsonb,
  after       jsonb,
  reason      text,
  actor_id    uuid NOT NULL REFERENCES staff(id),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CHECK (event = 'recorded' OR (reason IS NOT NULL AND btrim(reason) <> ''))
);
CREATE INDEX idx_patient_allergy_events_patient ON patient_allergy_events (tenant_id, patient_id, occurred_at DESC);
ALTER TABLE patient_allergy_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_allergy_events USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE OR REPLACE FUNCTION patient_allergy_events_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'patient_allergy_events is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_patient_allergy_events_no_update BEFORE UPDATE ON patient_allergy_events
  FOR EACH ROW EXECUTE FUNCTION patient_allergy_events_immutable();
CREATE TRIGGER trg_patient_allergy_events_no_delete BEFORE DELETE ON patient_allergy_events
  FOR EACH ROW EXECUTE FUNCTION patient_allergy_events_immutable();

-- 3. "None recorded" (nobody has asked) is different from "no known allergies" (a clinician asked and
--    documented it). One current row per patient; recording a real allergy supersedes it (see AllergyService).
CREATE TABLE patient_allergy_assessments (
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  patient_id    uuid NOT NULL REFERENCES patients(id),
  status        text NOT NULL CHECK (status IN ('no_known_allergies')),
  documented_by uuid NOT NULL REFERENCES staff(id),
  documented_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, patient_id)
);
ALTER TABLE patient_allergy_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_allergy_assessments USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- 4. Prescription warning decisions, one JSON array per item:
--    [{"kind":"allergy|pregnancy|controlled","reasonCode":"...","note":null,"matched":"Penicillin","severity":"severe",
--      "by":"<staff uuid>","byName":"...","at":"<iso instant>"}]
ALTER TABLE prescription_items
  ADD COLUMN IF NOT EXISTS overrides jsonb NOT NULL DEFAULT '[]'::jsonb;
