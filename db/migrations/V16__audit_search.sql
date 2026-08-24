-- Platform audit console (NB-272): cross-tenant, read-only audit search for
-- platform operators. audit_log's RLS (V14) is deliberately tenant-scoped —
-- one session can only ever see one tenant's rows, which is exactly right
-- for every other caller but wrong for this one. Rather than weaken that
-- policy, this is the one narrow, explicit escape hatch: SECURITY DEFINER,
-- locked down to nabd_app only, same precedent as find_session_by_token_hash
-- (V1). Every other read of audit_log still goes through the app role and
-- stays fully RLS-scoped.
-- Pagination cursor is just id, not (created_at, id) like the fleet/tickets
-- lists — audit_log.id is a bigint IDENTITY column, already a total order
-- on its own with no tie-breaking need.
CREATE FUNCTION search_audit_log(
  p_tenant_id uuid, p_action text, p_entity_type text,
  p_created_after timestamptz, p_created_before timestamptz,
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
    AND (p_after_id IS NULL OR al.id > p_after_id)
  ORDER BY al.id
  LIMIT p_limit;
$$;
REVOKE ALL ON FUNCTION search_audit_log(uuid, text, text, timestamptz, timestamptz, bigint, int) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int) TO <app_role>;
