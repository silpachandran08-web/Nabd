-- PLT-003 AC5: the per-IP login limit runs before the tenant is known, and login_attempts is FORCE-RLS, so a plain count
-- saw only tenant-less rows. This definer function counts across tenants and returns nothing but a number.
CREATE OR REPLACE FUNCTION login_attempts_count_from_ip(p_ip inet, p_since timestamptz) RETURNS integer
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
  SELECT count(*)::integer FROM login_attempts WHERE ip_address = p_ip AND attempted_at > p_since
$$;
REVOKE ALL ON FUNCTION login_attempts_count_from_ip(inet, timestamptz) FROM PUBLIC;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION login_attempts_count_from_ip(inet, timestamptz) TO nabd_app;
  END IF;
  -- on a fresh database V210 (later) creates the helper and adopts this function; only an already-migrated one has it now
  IF to_regproc('nabd_adopt_definer_functions') IS NOT NULL THEN PERFORM nabd_adopt_definer_functions(); END IF;
END $$;
