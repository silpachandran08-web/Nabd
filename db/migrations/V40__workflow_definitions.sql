-- NB-357 (Arogya Fabric restructuring, Phase 1): platform-authored workflow templates replace the
-- freely-reorderable owner-designed visit_flow_steps (V39/NB-355). An owner now picks one of a
-- fixed set of platform-published templates per department and flips the small set of toggles
-- that template defines — never reorders stages directly (CAD-06 "not a rules engine": owners
-- choose templates and toggles, the platform team authors definitions).

CREATE TABLE workflow_definitions (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code        text NOT NULL,
  version     int NOT NULL DEFAULT 1,
  -- NULL = platform-authored, shared by every tenant (the normal case). Set only for a one-off
  -- snapshot preserving a tenant's already-configured-but-nonstandard flow across this migration's
  -- backfill below — never created by any user-facing flow, and not selectable via the template
  -- picker (DepartmentRepository only looks up tenant_id IS NULL templates by code).
  tenant_id   uuid REFERENCES tenants(id),
  name        text NOT NULL,
  steps       jsonb NOT NULL,              -- ordered step types, e.g. ["billing","vitals","consultation"]
  toggle_keys jsonb NOT NULL DEFAULT '[]',  -- toggle keys this template accepts, e.g. ["vitals_enabled"]
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_workflow_definitions_platform ON workflow_definitions (code, version) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_workflow_definitions_tenant ON workflow_definitions (tenant_id, code, version) WHERE tenant_id IS NOT NULL;

-- Platform template library. Covers every real shape seen so far: the FRD default (vitals then
-- consultation, vitals optional via toggle), the general-clinic example from this session (upfront
-- consultation-fee billing before vitals), and the dental example (procedures then billing, no
-- vitals stage at all).
INSERT INTO workflow_definitions (code, version, name, steps, toggle_keys) VALUES
  ('clinic_walkin', 1, 'Clinic walk-in', '["vitals","consultation"]', '["vitals_enabled"]'),
  ('clinic_walkin_with_billing', 1, 'Clinic walk-in with upfront billing', '["billing","vitals","consultation"]', '["vitals_enabled"]'),
  ('dental_procedure', 1, 'Dental / procedure visit', '["consultation","procedures","billing"]', '[]');

CREATE TABLE department_workflow_selection (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              uuid NOT NULL REFERENCES tenants(id),
  department_id          uuid NOT NULL REFERENCES departments(id),
  workflow_definition_id uuid NOT NULL REFERENCES workflow_definitions(id),
  toggles                jsonb NOT NULL DEFAULT '{}',
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, department_id)
);
CREATE TRIGGER trg_department_workflow_selection_updated_at BEFORE UPDATE ON department_workflow_selection
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE department_workflow_selection ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON department_workflow_selection
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ── Backfill: map every department's currently-configured visit_flow_steps onto the closest
-- matching platform template so no tenant's live behavior changes. A department with no rows in
-- visit_flow_steps stays unconfigured (resolveStatusSequence's runtime default still applies).
-- Anything that doesn't match one of the three shapes above — only possible if an owner actually
-- used the brief-lived free-reorder UI to build something nonstandard — gets its own one-off
-- tenant-scoped snapshot template instead of being silently coerced onto the wrong shape. ──
DO $$
DECLARE
  dept RECORD;
  step_types text[];
  matched_code text;
  matched_id uuid;
  selection_toggles jsonb;
BEGIN
  FOR dept IN SELECT tenant_id, id FROM departments LOOP
    SELECT array_agg(step_type ORDER BY step_order) INTO step_types
      FROM visit_flow_steps WHERE tenant_id = dept.tenant_id AND department_id = dept.id;

    IF step_types IS NULL THEN
      CONTINUE;
    END IF;

    IF step_types = ARRAY['vitals','consultation'] THEN
      matched_code := 'clinic_walkin'; selection_toggles := '{"vitals_enabled": true}';
    ELSIF step_types = ARRAY['consultation'] THEN
      matched_code := 'clinic_walkin'; selection_toggles := '{"vitals_enabled": false}';
    ELSIF step_types = ARRAY['billing','vitals','consultation'] THEN
      matched_code := 'clinic_walkin_with_billing'; selection_toggles := '{"vitals_enabled": true}';
    ELSIF step_types = ARRAY['billing','consultation'] THEN
      matched_code := 'clinic_walkin_with_billing'; selection_toggles := '{"vitals_enabled": false}';
    ELSIF step_types = ARRAY['consultation','procedures','billing'] THEN
      matched_code := 'dental_procedure'; selection_toggles := '{}';
    ELSE
      matched_code := NULL;
    END IF;

    IF matched_code IS NOT NULL THEN
      SELECT id INTO matched_id FROM workflow_definitions WHERE code = matched_code AND tenant_id IS NULL;
    ELSE
      INSERT INTO workflow_definitions (tenant_id, code, version, name, steps, toggle_keys)
        VALUES (dept.tenant_id, 'custom_legacy_' || dept.id, 1, 'Custom (migrated)', to_jsonb(step_types), '[]')
        RETURNING id INTO matched_id;
      selection_toggles := '{}';
    END IF;

    INSERT INTO department_workflow_selection (tenant_id, department_id, workflow_definition_id, toggles)
      VALUES (dept.tenant_id, dept.id, matched_id, selection_toggles);
  END LOOP;
END $$;

DROP TABLE visit_flow_steps;
