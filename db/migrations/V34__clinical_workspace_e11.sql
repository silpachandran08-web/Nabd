-- E11 Clinical Workspace remainder (NB-104, NB-114, NB-116, NB-119).

-- NB-104: signed clinical notes are already immutable (NoteService blocks the update); amendments
-- append instead of overwriting, so the original text stays retrievable and every amendment carries
-- who made it, why, and what changed.
CREATE TABLE note_amendments (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  note_id       uuid NOT NULL REFERENCES clinical_notes(id),
  amended_by    uuid NOT NULL REFERENCES staff(id),
  reason        text NOT NULL,
  subjective    text, objective text, assessment text, plan text, diagnosis text,
  created_at    timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE note_amendments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON note_amendments USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_note_amendments_note ON note_amendments (note_id, created_at);

-- NB-114: saved prescription sets per doctor, or a clinic default when doctor_id is null. Applying
-- one still goes through PrescriptionService.upsert() — every allergy/pregnancy/controlled-substance
-- check runs exactly as if the items had been typed by hand.
CREATE TABLE favourite_rx_sets (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  doctor_id   uuid REFERENCES staff(id),
  name        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE favourite_rx_sets ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON favourite_rx_sets USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE favourite_rx_set_items (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  set_id         uuid NOT NULL REFERENCES favourite_rx_sets(id) ON DELETE CASCADE,
  drug_name      text NOT NULL,
  dosage         text, frequency text, duration text, instructions text,
  display_order  integer NOT NULL DEFAULT 0
);
ALTER TABLE favourite_rx_set_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON favourite_rx_set_items USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-116: marks a booking made from the consult page's "Schedule follow-up" action, distinguishing
-- it from an ordinary appointment so a missed one can be told apart on the callback list.
ALTER TABLE appointments ADD COLUMN is_follow_up boolean NOT NULL DEFAULT false;

-- NB-119: a typed-attestation signature (no canvas/stylus capture in this app) that gates a
-- procedure leaving 'ordered' — see ProcedureService.updateStatus.
ALTER TABLE procedure_orders
  ADD COLUMN consent_signed_name text,
  ADD COLUMN consent_recorded_by uuid REFERENCES staff(id),
  ADD COLUMN consent_signed_at timestamptz;
