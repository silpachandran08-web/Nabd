-- NB-360 (Arogya Fabric restructuring, Phase 4): begins reconciling queue_entries toward the
-- architecture doc's ENCOUNTER / ENCOUNTER_SEGMENT model (S6.5) without a physical table split.
-- queue_entries is FK-referenced by clinical_notes, invoices, prescriptions, procedure_orders and
-- more (V19-V29), so splitting it now would be a large, risky rewrite for a doc-fidelity win nothing
-- built so far needs. Instead: queue_entries stays what it always was -- one row per department leg,
-- i.e. an ENCOUNTER_SEGMENT -- and gains the columns that recover ENCOUNTER-level identity and the
-- doc's fixed stage vocabulary (S9.1) on top of it, kept in sync by triggers so no Java call site has
-- to remember to maintain them.

-- One visit, however many department legs it passes through (see V37's transfer graph), shares one
-- encounter_id: the first leg's points at itself, every transferred-to leg copies its parent's.
-- Recovers "everything that happened in this visit" with one WHERE clause instead of walking
-- parent_queue_entry_id by hand. Self-referencing FK, defaulted by trigger below rather than a
-- column DEFAULT (a DEFAULT expression can't see the row's own generated id).
ALTER TABLE queue_entries ADD COLUMN encounter_id uuid REFERENCES queue_entries(id);

CREATE FUNCTION queue_entries_default_encounter() RETURNS trigger AS $$
BEGIN
  IF NEW.encounter_id IS NULL THEN
    NEW.encounter_id := NEW.id;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_queue_entries_default_encounter BEFORE INSERT ON queue_entries
  FOR EACH ROW EXECUTE FUNCTION queue_entries_default_encounter();

UPDATE queue_entries SET encounter_id = id WHERE parent_queue_entry_id IS NULL;
UPDATE queue_entries child SET encounter_id = parent.encounter_id
  FROM queue_entries parent WHERE child.parent_queue_entry_id = parent.id AND child.encounter_id IS NULL;
ALTER TABLE queue_entries ALTER COLUMN encounter_id SET NOT NULL;
CREATE INDEX idx_queue_entries_encounter ON queue_entries (tenant_id, encounter_id);

-- Doc's ENCOUNTER.class -- every visit this app runs today is ambulatory (walk-in/appointment OPD);
-- procedure/teleconsult/inpatient classes are unbuilt, so this is a placeholder for a distinction
-- nothing yet makes.
ALTER TABLE queue_entries ADD COLUMN class text NOT NULL DEFAULT 'ambulatory'
  CHECK (class IN ('ambulatory', 'procedure', 'teleconsult', 'inpatient'));

-- Doc's fixed stage vocabulary (S9.1) layered over this leg's specific, tenant-configurable
-- `status` -- lets dashboards/wait-time math/a future token TV key off a stable set of stage types
-- regardless of how a department's workflow template names or orders its steps. A SQL function (not
-- duplicated CASE logic in a trigger and a Java map) is the single source of truth, used by both the
-- backfill below and the trigger that keeps every future write in sync.
CREATE FUNCTION stage_for_status(status text) RETURNS text AS $$
  SELECT CASE status
    WHEN 'checked_in' THEN 'CHECKED_IN'
    WHEN 'waiting' THEN 'WAITING'
    WHEN 'billing_pending' THEN 'AWAITING_PAYMENT'
    WHEN 'vitals_pending' THEN 'TRIAGE'
    WHEN 'vitals_done' THEN 'TRIAGE'
    WHEN 'in_consult' THEN 'IN_SERVICE'
    WHEN 'procedures_pending' THEN 'IN_SERVICE'
    WHEN 'checkout_pending' THEN 'AWAITING_PAYMENT'
    WHEN 'completed' THEN 'COMPLETED'
    WHEN 'no_show' THEN 'NO_SHOW'
    -- This leg's own segment is done the moment it hands off; the encounter continues in the next
    -- leg's segment, not this one -- the doc's vocabulary has no separate "transferred" stage.
    WHEN 'transferred_out' THEN 'COMPLETED'
  END;
$$ LANGUAGE sql IMMUTABLE;

ALTER TABLE queue_entries ADD COLUMN current_stage text;
UPDATE queue_entries SET current_stage = stage_for_status(status);
ALTER TABLE queue_entries ALTER COLUMN current_stage SET NOT NULL;

CREATE FUNCTION queue_entries_sync_current_stage() RETURNS trigger AS $$
BEGIN
  NEW.current_stage := stage_for_status(NEW.status);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_queue_entries_current_stage BEFORE INSERT OR UPDATE OF status ON queue_entries
  FOR EACH ROW EXECUTE FUNCTION queue_entries_sync_current_stage();

-- Which platform-authored template (NB-357) governs this leg, resolved once at insert from
-- whatever this department currently has selected -- null for an unconfigured department, matching
-- DepartmentService.resolveStatusSequence's own fallback everywhere else.
ALTER TABLE queue_entries ADD COLUMN workflow_definition_id uuid REFERENCES workflow_definitions(id);

CREATE FUNCTION queue_entries_default_workflow_definition() RETURNS trigger AS $$
BEGIN
  IF NEW.workflow_definition_id IS NULL THEN
    SELECT s.workflow_definition_id INTO NEW.workflow_definition_id
      FROM department_workflow_selection s
      WHERE s.tenant_id = NEW.tenant_id AND s.department_id = NEW.department_id;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_queue_entries_default_workflow BEFORE INSERT ON queue_entries
  FOR EACH ROW EXECUTE FUNCTION queue_entries_default_workflow_definition();

UPDATE queue_entries q SET workflow_definition_id = s.workflow_definition_id
  FROM department_workflow_selection s
  WHERE s.tenant_id = q.tenant_id AND s.department_id = q.department_id;
