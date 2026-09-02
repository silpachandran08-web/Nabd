-- NB-355: generalizes the single requires_vitals boolean into a fully configurable, reorderable
-- sequence of visit stages per department — check_in/waiting and checkout_pending/completed stay
-- fixed anchors (first/last), everything else (billing, vitals, consultation, procedures) is a
-- tenant-owned, positionable row here. consultation is mandatory (enforced in DepartmentService,
-- not the DB, for a clearer error message); the others are optional, zero-or-one each.
CREATE TABLE visit_flow_steps (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              uuid NOT NULL REFERENCES tenants(id),
  department_id          uuid NOT NULL REFERENCES departments(id),
  step_order             int NOT NULL,
  step_type              text NOT NULL CHECK (step_type IN ('billing', 'vitals', 'consultation', 'procedures')),
  -- Informational only: which functional team (e.g. Nursing, Billing) performs this stage. Not
  -- enforced anywhere yet — same "store it, don't gate on it yet" scope cut as nursing/arrivals
  -- staying unscoped by department from the first departments migration (V37).
  staffing_department_id uuid REFERENCES departments(id),
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, department_id, step_order),
  UNIQUE (tenant_id, department_id, step_type)
);
CREATE TRIGGER trg_visit_flow_steps_updated_at BEFORE UPDATE ON visit_flow_steps
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE visit_flow_steps ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit_flow_steps
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ── Backfill: every existing department's current requires_vitals value becomes an equivalent
-- flow (vitals only if it was true, consultation always), so live behavior for every
-- already-configured department is unchanged the moment this ships. ──
INSERT INTO visit_flow_steps (tenant_id, department_id, step_order, step_type)
SELECT tenant_id, id, 1, 'vitals' FROM departments WHERE requires_vitals;

INSERT INTO visit_flow_steps (tenant_id, department_id, step_order, step_type)
SELECT tenant_id, id, (CASE WHEN requires_vitals THEN 2 ELSE 1 END), 'consultation' FROM departments;

ALTER TABLE departments DROP COLUMN requires_vitals; -- superseded; visit_flow_steps is now the source of truth

ALTER TABLE queue_entries DROP CONSTRAINT queue_entries_status_check;
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_status_check CHECK (status IN
  ('checked_in', 'waiting', 'billing_pending', 'vitals_pending', 'vitals_done', 'in_consult',
   'procedures_pending', 'checkout_pending', 'completed', 'no_show', 'transferred_out'));
