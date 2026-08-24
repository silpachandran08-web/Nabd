-- Immutable audit trail (NB-238, SYS-13): who, what, when, before/after for
-- every PHI and financial action. Two independent tamper defenses, matching
-- the two acceptance criteria separately:
--   1. "survives application-level deletion" — trg_audit_log_immutable below
--      RAISEs on any UPDATE/DELETE/TRUNCATE, full stop. Deliberately a
--      trigger and not a REVOKE: ApiTestBase's bootstrapAppRole() (and this
--      project's local-dev setup) blanket-GRANTs UPDATE/DELETE on every
--      table in schema public to the app role, which would silently
--      re-open a REVOKE the moment either ran after this migration. A
--      trigger fires regardless of the caller's grants.
--   2. "tamper attempt detected" — a per-tenant hash chain (trg_audit_log_chain):
--      each row's hash covers its own content plus the previous row's hash
--      for that same tenant (the trigger's own SELECT is itself RLS-scoped,
--      since AuditService always sets app.tenant_id before inserting — see
--      TenantContext), so even a direct edit by a role that bypasses
--      triggers too (a superuser running ALTER TABLE ... DISABLE TRIGGER
--      first) breaks the chain and is detectable by recomputing hashes
--      forward per tenant (verify_audit_chain(), wrapped by AuditService.verify()).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE audit_log (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  actor_type  text NOT NULL CHECK (actor_type IN ('staff', 'operator', 'system')),
  actor_id    uuid,                     -- null for actor_type='system'
  actor_name  text NOT NULL,
  actor_role  text NOT NULL,            -- snapshot label: 'Owner' / staff role name / operator role / 'System'
  ip_address  inet,
  action      text NOT NULL,            -- "<entity>.<verb>", e.g. "patient.update"
  entity_type text NOT NULL,
  entity_id   uuid,
  before      jsonb,
  after       jsonb,
  created_at  timestamptz NOT NULL DEFAULT now(),
  prev_hash   text,                     -- null only for the first row in this tenant's chain
  row_hash    text NOT NULL
);

CREATE INDEX idx_audit_log_tenant_created ON audit_log (tenant_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Single source of truth for the hash formula — called from both the insert
-- trigger and verify_audit_chain() so they can never drift apart.
CREATE OR REPLACE FUNCTION audit_row_hash(
  p_prev_hash text, p_tenant_id uuid, p_actor_type text, p_actor_id uuid, p_actor_name text,
  p_actor_role text, p_ip_address inet, p_action text, p_entity_type text, p_entity_id uuid,
  p_before jsonb, p_after jsonb, p_created_at timestamptz
) RETURNS text AS $$
  SELECT encode(digest(
    coalesce(p_prev_hash, '') || '|' || p_tenant_id::text || '|' || p_actor_type || '|' ||
    coalesce(p_actor_id::text, '') || '|' || p_actor_name || '|' || p_actor_role || '|' ||
    coalesce(p_ip_address::text, '') || '|' || p_action || '|' || p_entity_type || '|' ||
    coalesce(p_entity_id::text, '') || '|' || coalesce(p_before::text, '') || '|' ||
    coalesce(p_after::text, '') || '|' || p_created_at::text,
    'sha256'), 'hex');
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION audit_log_chain() RETURNS trigger AS $$
DECLARE
  prev text;
BEGIN
  -- FOR UPDATE on the single latest row (RLS-scoped to this tenant) serializes
  -- concurrent inserts onto one chain instead of two transactions both reading
  -- the same prev_hash.
  SELECT row_hash INTO prev FROM audit_log ORDER BY id DESC LIMIT 1 FOR UPDATE;
  NEW.prev_hash := prev;
  NEW.row_hash := audit_row_hash(prev, NEW.tenant_id, NEW.actor_type, NEW.actor_id, NEW.actor_name,
      NEW.actor_role, NEW.ip_address, NEW.action, NEW.entity_type, NEW.entity_id, NEW.before, NEW.after,
      NEW.created_at);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_chain BEFORE INSERT ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_chain();

CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();

-- Walks one tenant's chain in id order, recomputing each row's hash with the
-- exact function the insert trigger used. Returns the id of the first row
-- whose stored hash (or prev_hash linkage) doesn't match, or NULL if intact.
CREATE OR REPLACE FUNCTION verify_audit_chain(p_tenant_id uuid) RETURNS bigint AS $$
DECLARE
  r record;
  prev text := NULL;
BEGIN
  FOR r IN SELECT * FROM audit_log WHERE tenant_id = p_tenant_id ORDER BY id LOOP
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
