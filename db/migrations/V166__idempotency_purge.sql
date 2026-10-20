-- PLT-026: hourly purge job (IdempotencyPurgeJob) runs as the DML-only app role, which FORCE RLS confines to one tenant;
SET lock_timeout = '5s';
-- the purge is cross-tenant housekeeping, so it goes through this narrow SECURITY DEFINER function.
CREATE FUNCTION purge_idempotency_keys() RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$ WITH d AS (DELETE FROM idempotency_keys WHERE expires_at < now() RETURNING 1) SELECT count(*)::int FROM d $$;

REVOKE ALL ON FUNCTION purge_idempotency_keys() FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION purge_idempotency_keys() TO nabd_app;
  END IF;
END $$;

-- V210 defines this helper (and adopts every SECURITY DEFINER function on a fresh database); call it only where it exists.
DO $$ BEGIN
  IF to_regproc('nabd_adopt_definer_functions()') IS NOT NULL THEN PERFORM nabd_adopt_definer_functions(); END IF;
END $$;
