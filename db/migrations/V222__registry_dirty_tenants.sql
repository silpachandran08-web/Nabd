SET lock_timeout = '5s';
-- OPS-007: the projector polls across tenants; this is the one narrow cross-tenant read it needs
-- (same precedent as claim_outbox_events, V44). It returns tenant ids only, never patient data.
CREATE FUNCTION registry_dirty_tenants(p_limit int) RETURNS SETOF uuid
  LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT DISTINCT tenant_id FROM registry_dirty LIMIT p_limit;
$$;
REVOKE ALL ON FUNCTION registry_dirty_tenants(int) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN GRANT EXECUTE ON FUNCTION registry_dirty_tenants(int) TO nabd_app; END IF;
END $$;
SELECT nabd_adopt_definer_functions();
