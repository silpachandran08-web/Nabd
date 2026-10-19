-- CLN-006 (MF-52): per-tooth clinical history in its own append-only table, so the dental chart's history
-- is not read from the audit store. DentalService writes one row per chart change; audit_log still gets its row.
SET lock_timeout = '5s';

CREATE TABLE dental_chart_events (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  -- Null only for backfilled history of a supernumerary tooth removed before V1232: audit_log never stored the patient.
  patient_id  uuid,
  entry_id    uuid NOT NULL,  -- dental_chart_entries.id; no FK because a removed supernumerary row is deleted
  tooth_number int NOT NULL,
  event       text NOT NULL CHECK (event IN ('set', 'supernumerary_added', 'supernumerary_changed', 'supernumerary_removed')),
  before      jsonb,
  after       jsonb,
  actor_id    uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT fk_dental_chart_events_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT fk_dental_chart_events_actor FOREIGN KEY (tenant_id, actor_id) REFERENCES staff (tenant_id, id)
);
CREATE INDEX idx_dental_chart_events_entry ON dental_chart_events (tenant_id, entry_id, occurred_at);

CREATE OR REPLACE FUNCTION dental_chart_events_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'dental_chart_events is append-only: % is not permitted', TG_OP
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_dental_chart_events_no_update BEFORE UPDATE ON dental_chart_events
  FOR EACH ROW EXECUTE FUNCTION dental_chart_events_immutable();
CREATE TRIGGER trg_dental_chart_events_no_delete BEFORE DELETE ON dental_chart_events
  FOR EACH ROW EXECUTE FUNCTION dental_chart_events_immutable();
CREATE TRIGGER trg_dental_chart_events_no_truncate BEFORE TRUNCATE ON dental_chart_events
  FOR EACH STATEMENT EXECUTE FUNCTION dental_chart_events_immutable();

-- ADR-07: reads over the visible tenant set, writes only to the session tenant. enforce_tenant_rls keeps the
-- visible arm because this seed policy mentions visible_tenant_ids, then replaces it with per-command policies.
ALTER TABLE dental_chart_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY seed_visible ON dental_chart_events
  USING (tenant_id = ANY (string_to_array(current_setting('app.visible_tenant_ids', true), ',')::uuid[]));
SELECT enforce_tenant_rls('dental_chart_events');
