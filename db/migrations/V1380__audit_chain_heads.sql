-- PLT-038 (MF-31, ARCH-07, DP-05, SEC-27): the audit hash chain no longer forks under concurrency.
-- V14/V126 found the predecessor with "SELECT row_hash ... ORDER BY id DESC LIMIT 1 FOR UPDATE": under READ COMMITTED
-- a waiter re-checks the row it locked, not whether a newer row appeared, so two writers chained onto the same
-- predecessor; and the id came from the identity default before the lock, so id order could differ from chain order.
-- Now each chain (one per tenant, plus the platform chain under the nil uuid) has one audit_chain_heads row that the
-- insert trigger locks, reads, and advances. The lock is per tenant: tenants never wait on each other.
-- Hash formula v2 hashes created_at as UTC epoch microseconds (v1 used created_at::text, which depends on the session
-- TimeZone); jsonb::text is already canonical (sorted, de-duplicated keys). Existing rows keep hash_v = 1 and verify
-- with the unchanged v1 formula.

LOCK TABLE audit_log IN SHARE ROW EXCLUSIVE MODE;  -- no audit insert may land between the head backfill and the trigger swap

ALTER TABLE audit_log ADD COLUMN hash_v smallint NOT NULL DEFAULT 1;  -- existing rows: v1 (metadata-only, no rewrite)
ALTER TABLE audit_log ALTER COLUMN hash_v SET DEFAULT 2;

CREATE TABLE audit_chain_heads (
  tenant_id  uuid PRIMARY KEY,           -- '00000000-0000-0000-0000-000000000000' = the platform chain (audit_log.tenant_id NULL)
  last_id    bigint NOT NULL,            -- 0 / '' = empty chain
  last_hash  text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('audit_chain_heads');

-- Trigger-only table: the app role reads and writes it solely through the SECURITY DEFINER trigger below.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN REVOKE ALL ON audit_chain_heads FROM nabd_app; END IF;
END $$;

-- Backfill one head per existing chain (migrator is under FORCE RLS on audit_log, so read as nabd_definer when we can).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    SET LOCAL ROLE nabd_definer;
  END IF;
  INSERT INTO audit_chain_heads (tenant_id, last_id, last_hash)
  SELECT t.id, h.id, h.row_hash FROM tenants t
  CROSS JOIN LATERAL (SELECT id, row_hash FROM audit_log WHERE tenant_id = t.id ORDER BY id DESC LIMIT 1) h;
  INSERT INTO audit_chain_heads (tenant_id, last_id, last_hash)
  SELECT '00000000-0000-0000-0000-000000000000', id, row_hash FROM audit_log WHERE tenant_id IS NULL ORDER BY id DESC LIMIT 1;
  RESET ROLE;
END $$;

CREATE OR REPLACE FUNCTION audit_row_hash_v2(
  p_prev_hash text, p_tenant_id uuid, p_actor_type text, p_actor_id uuid, p_actor_name text,
  p_actor_role text, p_ip_address inet, p_action text, p_entity_type text, p_entity_id uuid,
  p_before jsonb, p_after jsonb, p_created_at timestamptz
) RETURNS text AS $$
  SELECT encode(digest(
    'v2|' || coalesce(p_prev_hash, '') || '|' || coalesce(p_tenant_id::text, '') || '|' || p_actor_type || '|' ||
    coalesce(p_actor_id::text, '') || '|' || p_actor_name || '|' || p_actor_role || '|' ||
    coalesce(p_ip_address::text, '') || '|' || p_action || '|' || p_entity_type || '|' ||
    coalesce(p_entity_id::text, '') || '|' || coalesce(p_before::text, '') || '|' ||
    coalesce(p_after::text, '') || '|' || (extract(epoch FROM p_created_at) * 1000000)::bigint::text,
    'sha256'), 'hex');
$$ LANGUAGE sql IMMUTABLE SET search_path = public;

CREATE OR REPLACE FUNCTION audit_log_chain() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  k uuid := coalesce(NEW.tenant_id, '00000000-0000-0000-0000-000000000000');
  prev text;
  lid bigint;
  lhash text;
BEGIN
  -- A row lock on this chain's head; after a wait READ COMMITTED returns the head the previous writer committed.
  SELECT last_hash INTO prev FROM audit_chain_heads WHERE tenant_id = k FOR UPDATE;
  IF NOT FOUND THEN
    -- First write of a new chain: seed the head from the latest row (none for a new tenant). Concurrent seeders
    -- collide on the primary key; the loser waits, does nothing, and locks the winner's committed head.
    IF NEW.tenant_id IS NULL THEN
      SELECT id, row_hash INTO lid, lhash FROM audit_log WHERE tenant_id IS NULL ORDER BY id DESC LIMIT 1;
    ELSE
      SELECT id, row_hash INTO lid, lhash FROM audit_log WHERE tenant_id = NEW.tenant_id ORDER BY id DESC LIMIT 1;
    END IF;
    INSERT INTO audit_chain_heads (tenant_id, last_id, last_hash) VALUES (k, coalesce(lid, 0), coalesce(lhash, ''))
      ON CONFLICT (tenant_id) DO NOTHING;
    SELECT last_hash INTO STRICT prev FROM audit_chain_heads WHERE tenant_id = k FOR UPDATE;
  END IF;
  -- Take the id under the lock so id order equals chain order (the identity default ran before the wait).
  NEW.id := nextval(pg_get_serial_sequence('public.audit_log', 'id'));
  NEW.hash_v := 2;
  NEW.prev_hash := NULLIF(prev, '');
  NEW.row_hash := audit_row_hash_v2(NEW.prev_hash, NEW.tenant_id, NEW.actor_type, NEW.actor_id, NEW.actor_name,
      NEW.actor_role, NEW.ip_address, NEW.action, NEW.entity_type, NEW.entity_id, NEW.before, NEW.after,
      NEW.created_at);
  UPDATE audit_chain_heads SET last_id = NEW.id, last_hash = NEW.row_hash, updated_at = now() WHERE tenant_id = k;
  RETURN NEW;
END;
$$;

-- v1 rows recompute with V126's audit_row_hash (unchanged), v2 rows with audit_row_hash_v2; anything else is a break.
CREATE OR REPLACE FUNCTION verify_audit_chain(p_tenant_id uuid) RETURNS bigint AS $$
DECLARE
  r record;
  prev text := NULL;
  expected text;
BEGIN
  FOR r IN SELECT * FROM audit_log WHERE tenant_id IS NOT DISTINCT FROM p_tenant_id ORDER BY id LOOP
    expected := CASE r.hash_v
        WHEN 1 THEN audit_row_hash(prev, r.tenant_id, r.actor_type, r.actor_id, r.actor_name,
            r.actor_role, r.ip_address, r.action, r.entity_type, r.entity_id, r.before, r.after, r.created_at)
        WHEN 2 THEN audit_row_hash_v2(prev, r.tenant_id, r.actor_type, r.actor_id, r.actor_name,
            r.actor_role, r.ip_address, r.action, r.entity_type, r.entity_id, r.before, r.after, r.created_at)
      END;
    IF r.prev_hash IS DISTINCT FROM prev OR r.row_hash IS DISTINCT FROM expected
    THEN
      RETURN r.id;
    END IF;
    prev := r.row_hash;
  END LOOP;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

SELECT nabd_adopt_definer_functions();
