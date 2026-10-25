-- ACC-039: the cross-tenant sweep is the only thing that must see all tenants. It is a SECURITY DEFINER function owned by
-- the migrator role, which needs BYPASSRLS (or is a superuser): FORCE RLS applies to table owners too. See the runbook.
CREATE FUNCTION edge_purge_expired() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE n integer := 0; c integer;
BEGIN
    DELETE FROM booking_holds WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM conversations WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM processed_messages WHERE processed_at < now() - interval '7 days'; GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    RETURN n;
END $$;
REVOKE ALL ON FUNCTION edge_purge_expired() FROM PUBLIC;

-- The API role (DML only, no DDL) is created by the cell's provisioning; grants apply when it exists.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON conversations, processed_messages, booking_holds TO edge_app;
        GRANT EXECUTE ON FUNCTION edge_purge_expired() TO edge_app;
    END IF;
END $$;
