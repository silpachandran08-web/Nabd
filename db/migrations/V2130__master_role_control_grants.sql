-- PLT-009 (first half of the master role split): the control-plane role gets the master schema it will own.
-- nabd_control is created NOLOGIN by infra/db/bootstrap-roles.sql (the Control Plane's login user is made a member per cell by
-- IaC, like nabd_platform). Additive only: nabd_app KEEPS its master grants until the platform package runs on a nabd_control
-- DataSource (not done yet, see the PLT-009 report). The REVOKE comes with that change.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_control') THEN
    GRANT USAGE ON SCHEMA master TO nabd_control;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA master TO nabd_control;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA master TO nabd_control;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nabd_control;
    ALTER DEFAULT PRIVILEGES IN SCHEMA master GRANT USAGE, SELECT ON SEQUENCES TO nabd_control;
    GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int) TO nabd_control;
    GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int) TO nabd_control;
  END IF;
END $$;
