SET lock_timeout = '5s';
-- PHL-041: the lab test and panel catalogue (module lab, behind the `lab` entitlement). A test or panel keeps no price of its
-- own: charge_item_id is its price-list head (charge_catalogue, category Lab), written through organization.api.ChargeCatalogue,
-- so billing and the price change log have one source. decimals, sensitive, auto_release_to_patient and outsourced_partner_id
-- are for the result, release and partner-lab stories (PHL-044..051); nothing writes them yet.
CREATE TABLE lab_tests (
  id                      uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id               uuid NOT NULL REFERENCES tenants (id),
  code                    text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,23}$'),
  name                    text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 120),
  loinc                   text CHECK (loinc ~ '^[0-9]{1,7}-[0-9]$'),
  sample_type             text NOT NULL CHECK (btrim(sample_type) <> '' AND length(sample_type) <= 40),
  container               text NOT NULL CHECK (btrim(container) <> '' AND length(container) <= 40),
  volume_ml               numeric(6,2) CHECK (volume_ml > 0),
  method                  text CHECK (length(method) <= 80),
  unit                    text CHECK (length(unit) <= 40),
  decimals                int CHECK (decimals BETWEEN 0 AND 6),
  tat_minutes             int NOT NULL CHECK (tat_minutes BETWEEN 1 AND 43200),
  charge_item_id          uuid NOT NULL REFERENCES charge_catalogue (id),
  outsourced_partner_id   uuid,
  active                  boolean NOT NULL DEFAULT true,
  sensitive               boolean NOT NULL DEFAULT false,
  auto_release_to_patient boolean NOT NULL DEFAULT false,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  change_seq              bigint,
  UNIQUE (tenant_id, code)
);
CREATE TRIGGER trg_lab_tests_change_seq BEFORE INSERT OR UPDATE ON lab_tests
  FOR EACH ROW EXECUTE FUNCTION set_change_seq();
SELECT enforce_tenant_rls('lab_tests');

CREATE TABLE lab_panels (
  id             uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id      uuid NOT NULL REFERENCES tenants (id),
  code           text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,23}$'),
  name           text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 120),
  charge_item_id uuid NOT NULL REFERENCES charge_catalogue (id),
  active         boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  change_seq     bigint,
  UNIQUE (tenant_id, code)
);
CREATE TRIGGER trg_lab_panels_change_seq BEFORE INSERT OR UPDATE ON lab_panels
  FOR EACH ROW EXECUTE FUNCTION set_change_seq();
SELECT enforce_tenant_rls('lab_panels');

CREATE TABLE lab_panel_tests (
  tenant_id     uuid NOT NULL REFERENCES tenants (id),
  panel_id      uuid NOT NULL REFERENCES lab_panels (id) ON DELETE CASCADE,
  test_id       uuid NOT NULL REFERENCES lab_tests (id),
  display_order int NOT NULL CHECK (display_order > 0),
  PRIMARY KEY (panel_id, test_id)
);
CREATE INDEX idx_lab_panel_tests_tenant ON lab_panel_tests (tenant_id, panel_id, display_order);
SELECT enforce_tenant_rls('lab_panel_tests');

-- The Lab technician preset (RolePresets v2). Provisioning seeds it for new clinics; existing clinics keep their roles
-- (as with V1370) and build a lab role in the role editor.
ALTER TABLE roles DROP CONSTRAINT IF EXISTS roles_preset_code_check;
ALTER TABLE roles ADD CONSTRAINT roles_preset_code_check
  CHECK (preset_code IN ('owner','owner_doctor','doctor','nurse','receptionist','frontdesk_vitals','lab_technician')) NOT VALID;
ALTER TABLE roles VALIDATE CONSTRAINT roles_preset_code_check;

-- The built-in Owner role cannot be edited through the API (RoleService.update refuses it), so an existing clinic's Owner
-- would never reach the lab catalogue: give it the lab module, as V38 did for departments. Each changed role gets an audit
-- row (actor 'system'). The lab endpoints stay behind the clinic's lab entitlement.
DO $$
DECLARE
  t record;
  r record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    FOR r IN
      UPDATE roles ro
         SET grants = ro.grants || '[{"module":"lab","view":true,"create":true,"edit":true,"delete":true,"approve":true,"refundDiscount":true,"export":true,"breakGlass":false}]'::jsonb
       WHERE ro.tenant_id = t.id AND ro.built_in
         AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(ro.grants) g WHERE g->>'module' = 'lab')
      RETURNING ro.id
    LOOP
      INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, before, after)
      VALUES (t.id, 'system', NULL, 'System', 'System', 'role.owner_lab_grant_added', 'role', r.id,
              '{"lab": null}'::jsonb, '{"lab": "all", "source": "PHL-041"}'::jsonb);
    END LOOP;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
