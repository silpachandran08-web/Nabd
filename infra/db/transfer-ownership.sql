-- OPS-003: ONE-OFF for a database whose tables were created by the login the API used to run as (e.g. neondb_owner)
-- before the role split. The API refuses to start while it owns tables or has BYPASSRLS (RlsSelfCheck).
-- Order: 1) infra/db/bootstrap-roles.sql  2) THIS file, as the old owner/superuser  3) the migrate job as nabd_migrator
--        (it applies V210+ grants and FORCE ROW LEVEL SECURITY)  4) point the API at nabd_app (DB_USER / DB_PASSWORD).
--   psql -v ON_ERROR_STOP=1 -d nabd -f infra/db/transfer-ownership.sql
-- Skips objects owned by an extension. Safe to re-run.
-- Neon: the owner login is not a superuser, so it must be allowed to SET ROLE nabd_migrator to hand objects to it.
GRANT nabd_migrator TO CURRENT_USER;

DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT n.nspname, c.relname,
           CASE c.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END AS kind
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('public', 'master') AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
      AND pg_get_userbyid(c.relowner) <> 'nabd_migrator'
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'a' AND c.relkind = 'S')  -- identity/serial sequences follow their table
  LOOP
    EXECUTE format('ALTER %s %I.%I OWNER TO nabd_migrator', r.kind, r.nspname, r.relname);
  END LOOP;
  FOR r IN
    SELECT n.nspname, p.oid::regprocedure AS sig, CASE p.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END AS kind
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname IN ('public', 'master') AND p.prokind IN ('f', 'p')
      AND pg_get_userbyid(p.proowner) <> 'nabd_migrator'
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO nabd_migrator', r.kind, r.sig);
  END LOOP;
END $$;

REVOKE nabd_migrator FROM CURRENT_USER;  -- the old owner must not keep the migrator's rights
