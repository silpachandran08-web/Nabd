-- PLT-028: actor filter for the platform audit console (GET /v1/platform/audit-log and its CSV
-- export). Expand-only: V16's 7-argument search_audit_log() is left exactly as it is (old code
-- keeps working during a rolling deploy); this adds an 11-argument overload with four extra,
-- optional actor predicates. Every NULL argument means "no filter", same as V16.
--   p_actor_id   exact match on audit_log.actor_id (staff id / operator id)
--   p_actor_type exact match ('staff' | 'operator' | 'system')
--   p_actor_role case-insensitive exact match on the actor_role snapshot label
--   p_actor_name case-insensitive substring match on the actor_name snapshot; LIKE wildcards in the
--                input are escaped so '%' / '_' match literally
-- audit_log stores no actor email, so there is deliberately no email predicate.
-- Same SECURITY DEFINER escape hatch and lock-down as V16 (see that header for why).
CREATE FUNCTION search_audit_log(
  p_tenant_id uuid, p_action text, p_entity_type text,
  p_created_after timestamptz, p_created_before timestamptz,
  p_actor_id uuid, p_actor_type text, p_actor_role text, p_actor_name text,
  p_after_id bigint, p_limit int
) RETURNS TABLE (
  id bigint, tenant_id uuid, tenant_name text, tenant_slug text,
  actor_type text, actor_id uuid, actor_name text, actor_role text,
  ip_address inet, action text, entity_type text, entity_id uuid,
  before jsonb, after jsonb, created_at timestamptz
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT al.id, al.tenant_id, t.name, t.slug, al.actor_type, al.actor_id, al.actor_name,
         al.actor_role, al.ip_address, al.action, al.entity_type, al.entity_id, al.before, al.after,
         al.created_at
  FROM audit_log al JOIN tenants t ON al.tenant_id = t.id
  WHERE (p_tenant_id IS NULL OR al.tenant_id = p_tenant_id)
    AND (p_action IS NULL OR al.action = p_action)
    AND (p_entity_type IS NULL OR al.entity_type = p_entity_type)
    AND (p_created_after IS NULL OR al.created_at >= p_created_after)
    AND (p_created_before IS NULL OR al.created_at <= p_created_before)
    AND (p_actor_id IS NULL OR al.actor_id = p_actor_id)
    AND (p_actor_type IS NULL OR al.actor_type = p_actor_type)
    AND (p_actor_role IS NULL OR lower(al.actor_role) = lower(p_actor_role))
    AND (p_actor_name IS NULL OR al.actor_name ILIKE
         '%' || replace(replace(replace(p_actor_name, '\', '\\'), '%', '\%'), '_', '\_') || '%')
    AND (p_after_id IS NULL OR al.id > p_after_id)
  ORDER BY al.id
  LIMIT p_limit;
$$;
REVOKE ALL ON FUNCTION search_audit_log(uuid, text, text, timestamptz, timestamptz, uuid, text, text, text, bigint, int) FROM PUBLIC;

-- Carry over whatever EXECUTE grants the V16 signature already has (the deployment's app role), so
-- an environment that granted the old overload by hand does not start failing with "permission
-- denied" on the new one. A no-op where nothing was granted yet (e.g. the test bootstrap, which
-- grants both signatures itself in ApiTestBase.bootstrapAppRole after migrating).
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT DISTINCT grantee.rolname
    FROM pg_proc p
    CROSS JOIN LATERAL aclexplode(p.proacl) acl
    JOIN pg_roles grantee ON grantee.oid = acl.grantee
    WHERE p.oid = 'search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int)'::regprocedure
      AND acl.privilege_type = 'EXECUTE'
      AND acl.grantee <> p.proowner
  LOOP
    EXECUTE format(
      'GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int) TO %I',
      r.rolname);
  END LOOP;
END;
$$;
-- deployment must additionally (if not covered above): GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int) TO <app_role>;
