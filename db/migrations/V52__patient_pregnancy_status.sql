-- V52 (DOC-021): pregnancy / lactation status on the patient, modelled on the allergy register (V50).
--
-- "Not documented" is the ABSENCE of a row: nobody has asked. It is never stored and never shown as
-- "not pregnant". A row is a deliberate clinician statement (who/when kept), and every change is
-- appended to patient_pregnancy_events (append-only, enforced by trigger) as well as the audit log.
--
-- Expand-only: two new tables, nothing existing is rewritten.

CREATE TABLE IF NOT EXISTS patient_pregnancy_status (
  tenant_id              uuid NOT NULL REFERENCES tenants(id),
  patient_id             uuid NOT NULL REFERENCES patients(id),
  status                 text NOT NULL CHECK (status IN ('not_pregnant', 'pregnant', 'lactating', 'unknown')),
  expected_delivery_date date,
  recorded_by            uuid NOT NULL REFERENCES staff(id),
  recorded_at            timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, patient_id),
  CHECK (expected_delivery_date IS NULL OR status = 'pregnant')
);
ALTER TABLE patient_pregnancy_status ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_pregnancy_status USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE IF NOT EXISTS patient_pregnancy_events (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  patient_id  uuid NOT NULL REFERENCES patients(id),
  before      jsonb,
  after       jsonb NOT NULL,
  actor_id    uuid NOT NULL REFERENCES staff(id),
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_patient_pregnancy_events_patient ON patient_pregnancy_events (tenant_id, patient_id, occurred_at DESC);
ALTER TABLE patient_pregnancy_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_pregnancy_events USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE OR REPLACE FUNCTION patient_pregnancy_events_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'patient_pregnancy_events is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_patient_pregnancy_events_no_update BEFORE UPDATE ON patient_pregnancy_events
  FOR EACH ROW EXECUTE FUNCTION patient_pregnancy_events_immutable();
CREATE TRIGGER trg_patient_pregnancy_events_no_delete BEFORE DELETE ON patient_pregnancy_events
  FOR EACH ROW EXECUTE FUNCTION patient_pregnancy_events_immutable();
-- Row triggers do not fire on TRUNCATE; refuse it too (same guard V51 added for the allergy history).
DROP TRIGGER IF EXISTS trg_patient_pregnancy_events_no_truncate ON patient_pregnancy_events;
CREATE TRIGGER trg_patient_pregnancy_events_no_truncate BEFORE TRUNCATE ON patient_pregnancy_events
  FOR EACH STATEMENT EXECUTE FUNCTION patient_pregnancy_events_immutable();
