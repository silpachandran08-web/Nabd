SET lock_timeout = '5s';
-- PLT-021 (MF-04, CONS M4, ADR-07): remove brand-visibility widening for good. PLT-018 contained it behind a flag that was off in every
-- profile; the 20 widened tables still read "tenant_id = ANY (app_visible_tenant_ids())". Every such policy becomes single-tenant
-- ("tenant_id = app_tenant_id()"); WITH CHECK and DELETE were already own-tenant. Data moves between clinics only through an explicit,
-- consented, logged exchange (PLT-024 person link / CONS M2 chain directory), never through the database.
-- brands.data_visibility_scope stays until M5 (two releases later, expand/contract): db/deferred/M5__drop_brands_data_visibility_scope.sql.

DO $$
DECLARE
  p record;
  left_over int;
BEGIN
  FOR p IN SELECT pol.polname, pol.polrelid::regclass AS tbl,
                  pg_get_expr(pol.polqual, pol.polrelid) AS qual
             FROM pg_policy pol
            WHERE pg_get_expr(pol.polqual, pol.polrelid) ~ '(accessible|visible)_tenant_ids'
  LOOP
    EXECUTE format('ALTER POLICY %I ON %s USING (%s)', p.polname, p.tbl,
                   replace(p.qual, 'tenant_id = ANY (app_visible_tenant_ids())', 'tenant_id = app_tenant_id()'));
  END LOOP;
  SELECT count(*) INTO left_over FROM pg_policy pol
   WHERE pg_get_expr(pol.polqual, pol.polrelid) ~ '(accessible|visible)_tenant_ids'
      OR pg_get_expr(pol.polwithcheck, pol.polrelid) ~ '(accessible|visible)_tenant_ids';
  IF left_over > 0 THEN
    RAISE EXCEPTION 'PLT-021: % policy(ies) still read a visible/accessible tenant list in a shape this migration does not rewrite', left_over;
  END IF;
END $$;

-- Later tenant tables get single-tenant policies only: the "keep the widening this table had" branch is gone.
CREATE OR REPLACE FUNCTION enforce_tenant_rls(t regclass, null_arm text DEFAULT 'none') RETURNS void
  LANGUAGE plpgsql AS $$
DECLARE
  p record;
  own text := 'tenant_id = app_tenant_id()';
  vis text := 'tenant_id = app_tenant_id()';
  upd text;
BEGIN
  FOR p IN SELECT polname FROM pg_policy WHERE polrelid = t LOOP
    EXECUTE format('DROP POLICY %I ON %s', p.polname, t);
  END LOOP;
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
  upd := CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || vis ELSE vis END;  -- 'read' rows are never an UPDATE target
  IF null_arm IN ('read', 'rw') THEN vis := 'tenant_id IS NULL OR ' || vis; END IF;
  EXECUTE format('CREATE POLICY t_select ON %s FOR SELECT USING (%s)', t, vis);
  EXECUTE format('CREATE POLICY t_insert ON %s FOR INSERT WITH CHECK (%s)', t,
                 CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || own ELSE own END);
  EXECUTE format('CREATE POLICY t_update ON %s FOR UPDATE USING (%s) WITH CHECK (%s)', t, upd,
                 CASE WHEN null_arm = 'rw' THEN 'tenant_id IS NULL OR ' || own ELSE own END);
  EXECUTE format('CREATE POLICY t_delete ON %s FOR DELETE USING (%s)', t, own);
END $$;
