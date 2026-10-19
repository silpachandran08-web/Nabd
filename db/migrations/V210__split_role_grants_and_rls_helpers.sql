-- OPS-003 / ADR-07: grants for the split-role topology (infra/db/bootstrap-roles.sql creates the roles) and the
-- helper functions the per-command RLS policies of V211 read. Role-dependent parts are skipped when the role does
-- not exist, so a database that has not been bootstrapped yet still migrates (RlsSelfCheck then refuses to boot).

-- Policies read these, never raw current_setting(): NULLIF turns the '' a pooled connection keeps after a
-- transaction-local set_config into "no tenant" (zero rows) instead of a uuid cast error.
CREATE OR REPLACE FUNCTION app_tenant_id() RETURNS uuid
  LANGUAGE sql STABLE AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- The caller's own tenant plus every sibling its brand visibility scope opens up. TenantContext writes
-- app.accessible_tenant_ids today (siblings only); PLT-018 / T-010 writes app.visible_tenant_ids (including self).
-- Both are honoured so the policies work before and after that story lands.
CREATE OR REPLACE FUNCTION app_visible_tenant_ids() RETURNS uuid[]
  LANGUAGE sql STABLE AS $$
  SELECT COALESCE(array_agg(DISTINCT x::uuid), ARRAY[]::uuid[])
  FROM unnest(string_to_array(concat_ws(',',
         current_setting('app.tenant_id', true),
         current_setting('app.visible_tenant_ids', true),
         current_setting('app.accessible_tenant_ids', true)), ',')) AS x
  WHERE x <> ''
$$;

-- SECURITY DEFINER functions must run as a BYPASSRLS owner: with FORCE RLS the migrator would otherwise filter
-- them like any other session. Call this at the end of any migration that creates a SECURITY DEFINER function.
CREATE OR REPLACE FUNCTION nabd_adopt_definer_functions() RETURNS int
  LANGUAGE plpgsql AS $$
DECLARE
  r record;
  n int := 0;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') OR NOT pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    RAISE NOTICE 'nabd_definer not available to %: SECURITY DEFINER functions keep their owner', current_user;
    RETURN 0;
  END IF;
  FOR r IN
    SELECT p.oid::regprocedure AS fn
    FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
    WHERE p.prosecdef AND ns.nspname IN ('public', 'master')
      AND p.proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
  LOOP
    EXECUTE format('ALTER FUNCTION %s OWNER TO nabd_definer', r.fn);
    n := n + 1;
  END LOOP;
  RETURN n;
END $$;

DO $$
DECLARE
  r record;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT USAGE ON SCHEMA master TO nabd_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nabd_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nabd_app;
    GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA master TO nabd_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA master TO nabd_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nabd_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO nabd_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT SELECT, INSERT, UPDATE ON TABLES TO nabd_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT USAGE, SELECT ON SEQUENCES TO nabd_app;
    -- the "deployment must additionally: GRANT EXECUTE ... TO <app_role>" notes in V1/V2/V16/V18/V44/V46/V47/V85/V126
    FOR r IN SELECT p.oid::regprocedure AS fn FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
             WHERE p.prosecdef AND ns.nspname = 'public'
    LOOP
      EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO nabd_app', r.fn);
    END LOOP;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_reports') THEN
    GRANT USAGE ON SCHEMA public TO nabd_reports;
    GRANT SELECT ON ALL TABLES IN SCHEMA public TO nabd_reports;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO nabd_reports;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    GRANT USAGE ON SCHEMA master TO nabd_definer;
    GRANT ALL ON ALL TABLES IN SCHEMA public, master TO nabd_definer;
    GRANT ALL ON ALL SEQUENCES IN SCHEMA public, master TO nabd_definer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO nabd_definer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO nabd_definer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT ALL ON TABLES TO nabd_definer;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT ALL ON SEQUENCES TO nabd_definer;
    GRANT CREATE ON SCHEMA master TO nabd_definer;
    PERFORM nabd_adopt_definer_functions();
  END IF;
END $$;
