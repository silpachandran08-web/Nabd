-- OPS-055 / ADR-09: PgBouncer authentication. Run ONCE per Core database, AFTER bootstrap-roles.sql, as the admin user:
--   psql -v ON_ERROR_STOP=1 -v pgbouncer_auth_pw="$PGBOUNCER_AUTH_PW" -d nabd -f infra/db/bootstrap-pooler.sql
-- PgBouncer logs in as pgbouncer_auth and looks clients up with pgbouncer.get_auth(), so no client password hash is ever
-- copied into a file. Only the two pooled roles are resolvable: nabd_migrator and the admin user are DIRECT-only (DDL,
-- advisory locks). Safe to re-run (also rotates the password). pgbouncer_auth holds no table privilege.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pgbouncer_auth') THEN
    CREATE ROLE pgbouncer_auth LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;
  END IF;
END $$;
ALTER ROLE pgbouncer_auth PASSWORD :'pgbouncer_auth_pw';
ALTER ROLE pgbouncer_auth SET statement_timeout = '2s';

DO $$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO pgbouncer_auth', current_database()); END $$;

CREATE SCHEMA IF NOT EXISTS pgbouncer;
REVOKE ALL ON SCHEMA pgbouncer FROM PUBLIC;
GRANT USAGE ON SCHEMA pgbouncer TO pgbouncer_auth;

CREATE OR REPLACE FUNCTION pgbouncer.get_auth(p_usename text)
RETURNS TABLE (username text, password text)
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog
AS $$
  SELECT usename::text, passwd::text FROM pg_catalog.pg_shadow
  WHERE usename = p_usename AND usename IN ('nabd_app', 'nabd_reports')
$$;
REVOKE ALL ON FUNCTION pgbouncer.get_auth(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pgbouncer.get_auth(text) TO pgbouncer_auth;
