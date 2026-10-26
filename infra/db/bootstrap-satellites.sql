-- OPS-055 / ADR-01: separate databases and roles for the Comms Gateway (nabd_comms) and the Patient Access Edge (nabd_edge).
-- Run ONCE per cell as the admin user, connected to the maintenance database, AFTER bootstrap-roles.sql:
--   PGPASSWORD=<admin pw> psql -v ON_ERROR_STOP=1 -h <direct endpoint> -d postgres \
--     -v comms_migrator_pw=... -v comms_app_pw=... -v edge_migrator_pw=... -v edge_app_pw=... [-v core_db=nabd] \
--     -f infra/db/bootstrap-satellites.sql
-- Passwords come from the Secrets Manager entries Terraform creates (<cell>/db/<role>), never from this file.
-- Each service runs its own Flyway as <svc>_migrator and its API as <svc>_app (DML only), exactly like Core. None of these
-- roles can connect to the Core database or SELECT a Core table (proved by infra/db/test-satellite-grants.sh).
-- Safe to re-run (also rotates passwords).
\if :{?core_db}
\else
  \set core_db nabd
\endif

DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['comms_migrator', 'comms_app', 'edge_migrator', 'edge_app'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE', r);
    END IF;
  END LOOP;
END $$;
ALTER ROLE comms_migrator PASSWORD :'comms_migrator_pw';
ALTER ROLE comms_app PASSWORD :'comms_app_pw';
ALTER ROLE edge_migrator PASSWORD :'edge_migrator_pw';
ALTER ROLE edge_app PASSWORD :'edge_app_pw';
ALTER ROLE comms_app SET statement_timeout = '5s';
ALTER ROLE comms_app SET lock_timeout = '3s';
ALTER ROLE edge_app SET statement_timeout = '5s';
ALTER ROLE edge_app SET lock_timeout = '3s';

-- RDS: the admin is not a superuser and must be a member of a role to create a database owned by it.
GRANT comms_migrator TO CURRENT_USER;
GRANT edge_migrator TO CURRENT_USER;

SELECT 'CREATE DATABASE nabd_comms OWNER comms_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nabd_comms') \gexec
SELECT 'CREATE DATABASE nabd_edge OWNER edge_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nabd_edge') \gexec

-- Closed by default: PUBLIC may connect to nothing. Core's own roles keep the explicit CONNECT bootstrap-roles.sql gave them.
REVOKE ALL ON DATABASE nabd_comms FROM PUBLIC;
REVOKE ALL ON DATABASE nabd_edge FROM PUBLIC;
GRANT CONNECT ON DATABASE nabd_comms TO comms_app;
GRANT CONNECT ON DATABASE nabd_edge TO edge_app;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC, comms_migrator, comms_app, edge_migrator, edge_app', :'core_db') \gexec

\connect nabd_comms
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO comms_migrator;
GRANT USAGE ON SCHEMA public TO comms_app;
ALTER DEFAULT PRIVILEGES FOR ROLE comms_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO comms_app;
ALTER DEFAULT PRIVILEGES FOR ROLE comms_migrator IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO comms_app;

\connect nabd_edge
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO edge_migrator;
GRANT USAGE ON SCHEMA public TO edge_app;
ALTER DEFAULT PRIVILEGES FOR ROLE edge_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO edge_app;
ALTER DEFAULT PRIVILEGES FOR ROLE edge_migrator IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO edge_app;
