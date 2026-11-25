-- PLT-061: the service role (DML only, no DDL) is created by the cell's provisioning; grants apply when it exists.
-- The operator audit log stays append-only for it, as it is for nabd_app in Core (V1410); the triggers in V2 refuse
-- UPDATE, DELETE and TRUNCATE for every role.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'control_app') THEN
        GRANT SELECT, INSERT, UPDATE ON tenant_directory TO control_app;
        GRANT USAGE ON SCHEMA master TO control_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA master TO control_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA master TO control_app;
        REVOKE UPDATE, DELETE ON master.operator_audit_log FROM control_app;
    END IF;
END $$;
