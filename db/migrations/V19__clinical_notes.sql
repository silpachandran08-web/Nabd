-- E11 Clinical Workspace
-- Refs: NB-074 (Patient 360 last-visit derivation reads queue_entries — no schema change needed
-- there), NB-102 (consultation shell reuses queue_entries.status as its session strip — no schema
-- change needed there either), NB-103 (clinical note capture & templates — this table).
-- "Templates" is scoped down to a fixed SOAP structure (subjective/objective/assessment/plan) rather
-- than per-specialty templates — that's NB-121's specialty framework, not built yet.

CREATE TABLE clinical_notes (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  queue_entry_id  uuid NOT NULL UNIQUE REFERENCES queue_entries(id),
  patient_id      uuid NOT NULL REFERENCES patients(id),
  doctor_id       uuid NOT NULL REFERENCES staff(id),
  subjective      text,
  objective       text,
  assessment      text,
  plan            text,
  status          text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'signed')),
  signed_at       timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_clinical_notes_patient ON clinical_notes(tenant_id, patient_id, created_at DESC);
CREATE TRIGGER trg_clinical_notes_updated_at BEFORE UPDATE ON clinical_notes
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE clinical_notes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clinical_notes USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
