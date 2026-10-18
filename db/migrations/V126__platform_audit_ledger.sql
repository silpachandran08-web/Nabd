-- LIVE-08: a platform-level ledger inside the existing audit_log, for operator actions that concern no
-- single tenant (operator sign-in/out, plans, provisioning jobs before a tenant exists). Such rows carry
-- tenant_id NULL and form their own hash chain (the "platform chain"), next to the per-tenant chains.
-- Expand-only: no existing row changes and every existing row's hash stays identical (coalesce only
-- differs from V14's formula when tenant_id is NULL, which no row before this migration has).
--
-- Writes go through record_platform_audit(), a SECURITY DEFINER function (same escape-hatch precedent as
-- search_audit_log, V16/V85): the app role stays under audit_log's tenant RLS policy, which can never match
-- a NULL tenant, so it cannot insert or read platform rows directly. Platform rows are read only through
-- search_audit_log (redefined below with a LEFT JOIN so they appear, tenant name/slug NULL).

ALTER TABLE audit_log ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_platform_rows_are_operator_or_system
  CHECK (tenant_id IS NOT NULL OR actor_type IN ('operator', 'system'));

CREATE INDEX idx_audit_log_platform ON audit_log (id) WHERE tenant_id IS NULL;

CREATE OR REPLACE FUNCTION audit_row_hash(
  p_prev_hash text, p_tenant_id uuid, p_actor_type text, p_actor_id uuid, p_actor_name text,
  p_actor_role text, p_ip_address inet, p_action text, p_entity_type text, p_entity_id uuid,
  p_before jsonb, p_after jsonb, p_created_at timestamptz
) RETURNS text AS $$
  SELECT encode(digest(
    coalesce(p_prev_hash, '') || '|' || coalesce(p_tenant_id::text, '') || '|' || p_actor_type || '|' ||
    coalesce(p_actor_id::text, '') || '|' || p_actor_name || '|' || p_actor_role || '|' ||
    coalesce(p_ip_address::text, '') || '|' || p_action || '|' || p_entity_type || '|' ||
    coalesce(p_entity_id::text, '') || '|' || coalesce(p_before::text, '') || '|' ||
    coalesce(p_after::text, '') || '|' || p_created_at::text,
    'sha256'), 'hex');
$$ LANGUAGE sql IMMUTABLE;

-- The chain predecessor is now selected explicitly by scope instead of relying on RLS alone: inside
-- record_platform_audit() the trigger runs as the definer (RLS bypassed), so an unscoped "latest row"
-- would link the platform chain to some tenant's row. For tenant rows the explicit filter is the same set
-- RLS already gave (V14's policy is tenant_id = app.tenant_id), and matches verify_audit_chain's walk.
CREATE OR REPLACE FUNCTION audit_log_chain() RETURNS trigger AS $$
DECLARE
  prev text;
BEGIN
  IF NEW.tenant_id IS NULL THEN
    SELECT row_hash INTO prev FROM audit_log WHERE tenant_id IS NULL ORDER BY id DESC LIMIT 1 FOR UPDATE;
  ELSE
    SELECT row_hash INTO prev FROM audit_log WHERE tenant_id = NEW.tenant_id ORDER BY id DESC LIMIT 1 FOR UPDATE;
  END IF;
  NEW.prev_hash := prev;
  NEW.row_hash := audit_row_hash(prev, NEW.tenant_id, NEW.actor_type, NEW.actor_id, NEW.actor_name,
      NEW.actor_role, NEW.ip_address, NEW.action, NEW.entity_type, NEW.entity_id, NEW.before, NEW.after,
      NEW.created_at);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- verify_audit_chain(NULL) now walks the platform chain.
CREATE OR REPLACE FUNCTION verify_audit_chain(p_tenant_id uuid) RETURNS bigint AS $$
DECLARE
  r record;
  prev text := NULL;
BEGIN
  FOR r IN SELECT * FROM audit_log WHERE tenant_id IS NOT DISTINCT FROM p_tenant_id ORDER BY id LOOP
    IF r.prev_hash IS DISTINCT FROM prev
       OR r.row_hash != audit_row_hash(prev, r.tenant_id, r.actor_type, r.actor_id, r.actor_name,
            r.actor_role, r.ip_address, r.action, r.entity_type, r.entity_id, r.before, r.after, r.created_at)
    THEN
      RETURN r.id;
    END IF;
    prev := r.row_hash;
  END LOOP;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION record_platform_audit(
  p_actor_type text, p_actor_id uuid, p_actor_name text, p_actor_role text, p_ip_address inet,
  p_action text, p_entity_type text, p_entity_id uuid, p_before jsonb, p_after jsonb
) RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address,
                         action, entity_type, entity_id, before, after)
  VALUES (NULL, p_actor_type, p_actor_id, p_actor_name, p_actor_role, p_ip_address,
          p_action, p_entity_type, p_entity_id, p_before, p_after);
$$;
REVOKE ALL ON FUNCTION record_platform_audit(text, uuid, text, text, inet, text, text, uuid, jsonb, jsonb) FROM PUBLIC;

-- Same 11-argument signature and result as V85 (so existing grants stay); only JOIN -> LEFT JOIN.
CREATE OR REPLACE FUNCTION search_audit_log(
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
  FROM audit_log al LEFT JOIN tenants t ON al.tenant_id = t.id
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

-- Give record_platform_audit the same EXECUTE grantees as search_audit_log (the deployment's app role).
-- A no-op where nothing was granted yet (the test bootstrap grants it itself in ApiTestBase).
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT DISTINCT grantee.rolname
    FROM pg_proc p
    CROSS JOIN LATERAL aclexplode(p.proacl) acl
    JOIN pg_roles grantee ON grantee.oid = acl.grantee
    WHERE p.oid = 'search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int)'::regprocedure
      AND acl.privilege_type = 'EXECUTE'
      AND acl.grantee <> p.proowner
  LOOP
    EXECUTE format(
      'GRANT EXECUTE ON FUNCTION record_platform_audit(text,uuid,text,text,inet,text,text,uuid,jsonb,jsonb) TO %I',
      r.rolname);
  END LOOP;
END;
$$;
-- deployment must additionally (if not covered above): GRANT EXECUTE ON FUNCTION record_platform_audit(text,uuid,text,text,inet,text,text,uuid,jsonb,jsonb) TO <app_role>;
