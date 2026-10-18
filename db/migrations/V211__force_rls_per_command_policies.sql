-- OPS-003 (MF-03, MF-63): every tenant_id table gets FORCE ROW LEVEL SECURITY and per-command policies:
--   SELECT / UPDATE-target rows: tenant_id in the caller's visible set   (own tenant, or siblings where V6/V42 already allowed it)
--   INSERT / UPDATE new row    : WITH CHECK tenant_id = the caller's own tenant   (a widened read never becomes a cross-tenant write)
--   DELETE                     : own tenant only
-- A table that did not read the sibling GUC before still does not (own tenant only): widening more tables is PLT-018's call.
-- Data backfills in later migrations on these tables must run as nabd_definer (SET LOCAL ROLE): the migrator is subject to FORCE.

-- package_eligible_doctors joined through packages only; give it its own tenant_id (default keeps PackageRepository unchanged).
ALTER TABLE package_eligible_doctors ADD COLUMN tenant_id uuid REFERENCES tenants(id) DEFAULT app_tenant_id();
UPDATE package_eligible_doctors ed SET tenant_id = p.tenant_id FROM packages p WHERE p.id = ed.package_id;
ALTER TABLE package_eligible_doctors ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX idx_package_eligible_doctors_tenant ON package_eligible_doctors (tenant_id);

-- null_arm: 'none' (default) | 'read' (tenant_id IS NULL rows readable, never writable: platform templates)
--           | 'rw' (NULL rows readable and writable: pre-resolution login telemetry).
-- Idempotent and reusable: later migrations that create a tenant table call SELECT enforce_tenant_rls('my_table').
CREATE OR REPLACE FUNCTION enforce_tenant_rls(t regclass, null_arm text DEFAULT 'none') RETURNS void
  LANGUAGE plpgsql AS $$
DECLARE
  widen boolean;
  p record;
  own text := 'tenant_id = app_tenant_id()';
  vis text;
  upd text;
BEGIN
  SELECT COALESCE(bool_or(pg_get_expr(polqual, polrelid) ~ '(accessible|visible)_tenant_ids'), false)
    INTO widen FROM pg_policy WHERE polrelid = t;
  FOR p IN SELECT polname FROM pg_policy WHERE polrelid = t LOOP
    EXECUTE format('DROP POLICY %I ON %s', p.polname, t);
  END LOOP;
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
  vis := CASE WHEN widen THEN 'tenant_id = ANY (app_visible_tenant_ids())' ELSE own END;
  upd := CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || vis ELSE vis END;  -- 'read' rows are never an UPDATE target
  IF null_arm IN ('read', 'rw') THEN vis := 'tenant_id IS NULL OR ' || vis; END IF;
  EXECUTE format('CREATE POLICY t_select ON %s FOR SELECT USING (%s)', t, vis);
  EXECUTE format('CREATE POLICY t_insert ON %s FOR INSERT WITH CHECK (%s)', t,
                 CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || own ELSE own END);
  EXECUTE format('CREATE POLICY t_update ON %s FOR UPDATE USING (%s) WITH CHECK (%s)', t, upd,
                 CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || own ELSE own END);
  EXECUTE format('CREATE POLICY t_delete ON %s FOR DELETE USING (%s)', t, own);
END $$;

DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT c.oid::regclass AS tbl, c.relname
    FROM pg_class c
    JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
    WHERE c.relkind IN ('r', 'p')
      AND c.relname <> 'tenant_lifecycle_events'  -- operator-plane audit, exempted in rls-exemptions.txt
    ORDER BY c.relname
  LOOP
    PERFORM enforce_tenant_rls(r.tbl, CASE r.relname WHEN 'workflow_definitions' THEN 'read'
                                                     WHEN 'login_attempts' THEN 'rw' ELSE 'none' END);
  END LOOP;
END $$;
