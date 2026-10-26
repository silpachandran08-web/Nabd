SET lock_timeout = '5s';
-- COM-007: least privilege. comms_app gets DML on this database only; it cannot even connect to any other database
-- (Core's included), and has no grant on any table it does not own here (CommsGrantTest proves it).
DO $$ BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO comms_app, comms_migrator', current_database());
END $$;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO comms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO comms_app;
REVOKE ALL ON flyway_schema_history FROM comms_app; -- the serving role has no business reading migration history
-- tables added by later migrations (V3-V5, run by the same migrator) follow automatically
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO comms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO comms_app;
