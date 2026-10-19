-- OPS-003 / ADR-07: split database roles. Run ONCE per database, BEFORE the migrate job, as a superuser:
--   psql -v ON_ERROR_STOP=1 -v migrator_pw="$MIGRATOR_PW" -v app_pw="$APP_PW" -v reports_pw="$REPORTS_PW" \
--        -d nabd -f infra/db/bootstrap-roles.sql
-- Passwords come from the secret manager / CI secrets, never from this file. Safe to re-run (also rotates passwords).
--
--   nabd_migrator  owns every table and function, runs Flyway (the migrate job only). NOBYPASSRLS.
--   nabd_app       the API: DML only, owns nothing, NOBYPASSRLS, short role-level timeouts.
--   nabd_reports   read-only, 60 s statements.
--   nabd_definer   NOLOGIN, BYPASSRLS: owns SECURITY DEFINER functions (see V210); migrator is a member.
-- Table/sequence/function grants are applied by V210 (and default privileges), not here, so they follow the schema.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_migrator') THEN
    CREATE ROLE nabd_migrator LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    CREATE ROLE nabd_app LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_reports') THEN
    CREATE ROLE nabd_reports LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') THEN
    CREATE ROLE nabd_definer NOLOGIN NOSUPERUSER BYPASSRLS;
  END IF;
END $$;

ALTER ROLE nabd_migrator PASSWORD :'migrator_pw';
ALTER ROLE nabd_app PASSWORD :'app_pw';
ALTER ROLE nabd_reports PASSWORD :'reports_pw';

-- Role-level (not session-level) settings: survive PgBouncer transaction pooling.
ALTER ROLE nabd_app SET statement_timeout = '5s';
ALTER ROLE nabd_app SET lock_timeout = '3s';
ALTER ROLE nabd_app SET idle_in_transaction_session_timeout = '15s';
ALTER ROLE nabd_reports SET statement_timeout = '60s';
ALTER ROLE nabd_reports SET lock_timeout = '3s';
ALTER ROLE nabd_reports SET idle_in_transaction_session_timeout = '60s';

GRANT nabd_definer TO nabd_migrator;

DO $$
BEGIN
  EXECUTE format('GRANT CONNECT, CREATE ON DATABASE %I TO nabd_migrator', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO nabd_app, nabd_reports', current_database());
END $$;

GRANT USAGE, CREATE ON SCHEMA public TO nabd_migrator, nabd_definer;  -- CREATE: a role must have it to own functions in the schema
GRANT USAGE ON SCHEMA public TO nabd_app, nabd_reports;
