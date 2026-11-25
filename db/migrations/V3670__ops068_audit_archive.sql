SET lock_timeout = '5s';
-- OPS-068 (DP-11, MF-65, SEC-28): archive of sealed months of the two audit stores. Design: docs/architecture/audit-partitioning.md.
-- This file changes no existing table: it adds the retention policy and manifest tables, the functions the archive job
-- (AuditArchiveService) calls, and one rule in verify_audit_chain(): a chain may start from the signed seal of an archived month.
-- A month is detached and dropped only through audit_archive_drop(), which refuses anything inside the approved online retention.

-- Online retention per region and data class. No row is seeded: the periods need the DPO's and counsel's approval (T-238, T-273),
-- and the archive job acts only on rows with approved_at set.
CREATE TABLE data_retention_policies (
  region        char(2) NOT NULL REFERENCES regions(code),
  data_class    text NOT NULL CHECK (data_class IN ('audit_log', 'phi_read_log')),
  online_months int NOT NULL CHECK (online_months >= 1),
  archive_years int NOT NULL CHECK (archive_years >= 0),
  legal_basis   text,
  approved_by   text,
  approved_at   timestamptz,
  PRIMARY KEY (region, data_class),
  CONSTRAINT data_retention_policies_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL))
);

-- One row per archived partition; written only by audit_archive_drop() in the transaction that drops the partition.
CREATE TABLE archive_manifests (
  table_name     text NOT NULL CHECK (table_name IN ('audit_log', 'phi_read_log')),
  partition_name text NOT NULL,
  month          date NOT NULL CHECK (month = date_trunc('month', month)::date),
  region         text NOT NULL,
  object_key     text NOT NULL,
  row_count      bigint NOT NULL CHECK (row_count >= 0),
  sha256         text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  archived_at    timestamptz NOT NULL DEFAULT now(),
  restored_at    timestamptz,  -- last successful restore drill
  PRIMARY KEY (table_name, partition_name)
);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE INSERT, UPDATE, DELETE ON data_retention_policies, archive_manifests FROM nabd_app;
  END IF;
END $$;

-- True when the first row still online legitimately follows rows that were archived: its prev_hash is the chain head that
-- AuditChainSealer signed (audit_chain_seals) for a month that archive_manifests records as archived.
CREATE FUNCTION audit_chain_anchor_ok(p_tenant_id uuid, p_first_id bigint, p_prev_hash text, p_created_at timestamptz) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT EXISTS (
    SELECT 1 FROM audit_chain_seals s
      JOIN archive_manifests m ON m.table_name = 'audit_log' AND m.month = s.month
     WHERE s.tenant_id = COALESCE(p_tenant_id, '00000000-0000-0000-0000-000000000000')
       AND s.last_hash = p_prev_hash AND s.last_id < p_first_id
       AND s.month < date_trunc('month', p_created_at AT TIME ZONE 'UTC')::date)
$$;

-- V1380's walk, plus one rule: the first online row may start from the seal of an archived month instead of from nothing.
CREATE OR REPLACE FUNCTION verify_audit_chain(p_tenant_id uuid) RETURNS bigint AS $$
DECLARE
  r record;
  prev text := NULL;
  expected text;
  first boolean := true;
BEGIN
  FOR r IN SELECT * FROM audit_log WHERE tenant_id IS NOT DISTINCT FROM p_tenant_id ORDER BY id LOOP
    IF first AND r.prev_hash IS NOT NULL AND audit_chain_anchor_ok(p_tenant_id, r.id, r.prev_hash, r.created_at) THEN
      prev := r.prev_hash;
    END IF;
    first := false;
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

-- ---- archive (AuditArchiveService) -----------------------------------------------------------------------------------------
-- p_partition is always checked against the catalogue: it must be a monthly partition of audit_log or phi_read_log.
CREATE FUNCTION audit_archive_parent(p_partition text) RETURNS text
  LANGUAGE sql STABLE SET search_path = public AS $$
  SELECT CASE p.relname WHEN 'audit_log_months' THEN 'audit_log' ELSE p.relname::text END
    FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
   WHERE c.relname = p_partition AND c.relnamespace = 'public'::regnamespace
     AND p.relname IN ('audit_log_months', 'phi_read_log') AND p_partition ~ '^(audit_log|phi_read_log)_y[0-9]{4}m[0-9]{2}$'
$$;

-- Every row of one month as a JSON line, in id order (the archive file's content).
-- shortcut: the set is materialised (a temp file past work_mem); page it by id when a month passes a few GB.
CREATE FUNCTION audit_archive_rows(p_partition text) RETURNS TABLE (id bigint, line text)
  LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF audit_archive_parent(p_partition) IS NULL THEN
    RAISE EXCEPTION 'OPS-068: % is not a monthly audit partition', p_partition;
  END IF;
  -- this reads every tenant's rows past RLS: only for a month that may leave the database anyway (security note, section 11)
  IF NOT audit_archive_due(p_partition) THEN
    RAISE EXCEPTION 'OPS-068: % is inside the online retention, or a region with rows in it has no approved policy', p_partition;
  END IF;
  RETURN QUERY EXECUTE format('SELECT t.id, to_jsonb(t)::text FROM %I t ORDER BY t.id', p_partition);
END $$;

-- phi_read_log: batches of this month whose stored batch_hash (V1390, seal_phi_read_batches) no longer matches their rows,
-- plus rows not sealed yet. A batch that also has rows in another month is not recomputed here.
CREATE FUNCTION phi_read_archive_bad_batches(p_partition text) RETURNS bigint
  LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
  n bigint;
BEGIN
  IF audit_archive_parent(p_partition) IS DISTINCT FROM 'phi_read_log' THEN
    RAISE EXCEPTION 'OPS-068: % is not a monthly phi_read_log partition', p_partition;
  END IF;
  EXECUTE format($q$
    SELECT (SELECT count(*) FROM %1$I WHERE batch_id IS NULL)
         + (SELECT count(*) FROM (
              SELECT 1 FROM %1$I s JOIN phi_read_batches b ON b.tenant_id = s.tenant_id AND b.batch_id = s.batch_id
               GROUP BY b.tenant_id, b.batch_id, b.prev_batch_hash, b.batch_hash, b.row_count
              HAVING count(*) = b.row_count
                 AND b.batch_hash <> encode(sha256(convert_to(b.prev_batch_hash || E'\n' || string_agg(
                       phi_read_row_text(s.id, s.read_at, s.actor_type, s.actor_id, s.session_id, s.patient_id, s.resource_type,
                                         s.resource_id, s.endpoint, s.purpose_code, s.break_glass_grant_id, s.ip, s.request_id),
                       E'\n' ORDER BY s.id), 'UTF8')), 'hex')) x)
  $q$, p_partition) INTO n;
  RETURN n;
END $$;

-- May this month leave the database? Yes when every clinic with rows in it belongs to a region whose policy for the table is
-- approved, and the month is older than the longest online_months among those regions and every approved policy of the
-- table (platform rows and empty months have no region of their own). No approved policy: nothing may leave.
CREATE FUNCTION audit_archive_due(p_partition text) RETURNS boolean
  LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_table  text := audit_archive_parent(p_partition);
  v_month  date;
  v_now    date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
  v_ok     boolean;
  v_months int;
BEGIN
  IF v_table IS NULL THEN
    RAISE EXCEPTION 'OPS-068: % is not a monthly audit partition', p_partition;
  END IF;
  v_month := to_date(right(p_partition, 7), 'YYYY"m"MM');
  SELECT min(online_months) INTO v_months FROM data_retention_policies WHERE data_class = v_table AND approved_at IS NOT NULL;
  IF v_months IS NULL OR v_month >= v_now - make_interval(months => v_months) THEN
    RETURN false;  -- too recent under any approved policy: decided without reading the month
  END IF;
  EXECUTE format($q$
    SELECT COALESCE(bool_and(q.ok), false), max(q.months) FROM (
      SELECT p.approved_at IS NOT NULL AS ok, p.online_months AS months
        FROM (SELECT DISTINCT tenant_id FROM %I WHERE tenant_id IS NOT NULL) x
        LEFT JOIN tenants t ON t.id = x.tenant_id
        LEFT JOIN data_retention_policies p ON p.region = t.region_code AND p.data_class = $1
      UNION ALL
      SELECT true, p.online_months FROM data_retention_policies p WHERE p.data_class = $1 AND p.approved_at IS NOT NULL) q
  $q$, p_partition) INTO v_ok, v_months USING v_table;
  RETURN v_ok AND v_month < v_now - make_interval(months => v_months);
END $$;

-- The chain head of every tenant in one audit_log month (tenant_id NULL = the platform chain), and whether the month is one
-- unbroken stretch of that chain: false when a row of a later month carries a lower id (a transaction that began before
-- midnight UTC and wrote after), in which case the seal of the month is not what the next online row points at.
CREATE FUNCTION audit_archive_heads(p_partition text)
  RETURNS TABLE (tenant_id uuid, last_id bigint, last_hash text, contiguous boolean)
  LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS $$
DECLARE
  hi timestamptz;
BEGIN
  IF audit_archive_parent(p_partition) IS DISTINCT FROM 'audit_log' THEN
    RAISE EXCEPTION 'OPS-068: % is not a monthly audit_log partition', p_partition;
  END IF;
  hi := ((to_date(right(p_partition, 7), 'YYYY"m"MM') + interval '1 month')::date::text || ' 00:00:00+00')::timestamptz;
  RETURN QUERY EXECUTE format($q$
    SELECT h.tenant_id, h.id, h.row_hash,
           NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.tenant_id = h.tenant_id AND a.id < h.id AND a.created_at >= $1)
       AND NOT (h.tenant_id IS NULL AND EXISTS (SELECT 1 FROM audit_log a WHERE a.tenant_id IS NULL AND a.id < h.id AND a.created_at >= $1))
      FROM (SELECT DISTINCT ON (p.tenant_id) p.tenant_id, p.id, p.row_hash FROM %I p ORDER BY p.tenant_id, p.id DESC) h
  $q$, p_partition) USING hi;
END $$;

-- The last step of an archive, one transaction: the month must be due (audit_archive_due) and still hold exactly
-- what was exported; then it is detached and dropped and the manifest row written. p_platform_signature seals the platform
-- chain (tenant_id NULL), which the monthly seal job does not cover. The SHARE lock stops writers of this month only; DETACH
-- takes ACCESS EXCLUSIVE on the direct parent for an instant (lock_timeout 5s: the job retries the next day).
CREATE FUNCTION audit_archive_drop(p_partition text, p_row_count bigint, p_max_id bigint, p_object_key text,
                                   p_sha256 text, p_platform_signature text) RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public SET lock_timeout = '5s' AS $$
DECLARE
  v_table  text := audit_archive_parent(p_partition);
  v_month  date;
  v_region text;
  v_rows   bigint;
  v_max    bigint;
BEGIN
  IF v_table IS NULL THEN
    RAISE EXCEPTION 'OPS-068: % is not a monthly audit partition', p_partition;
  END IF;
  v_month := to_date(right(p_partition, 7), 'YYYY"m"MM');
  IF NOT audit_archive_due(p_partition) THEN
    RAISE EXCEPTION 'OPS-068: % is inside the online retention, or a region with rows in it has no approved policy', p_partition;
  END IF;
  EXECUTE format('LOCK TABLE %I IN SHARE MODE', p_partition);
  EXECUTE format('SELECT count(*), max(id) FROM %I', p_partition) INTO v_rows, v_max;
  IF v_rows <> p_row_count OR v_max IS DISTINCT FROM p_max_id THEN
    RAISE EXCEPTION 'OPS-068: % changed since it was exported (% rows, max id %; exported % rows, max id %)',
      p_partition, v_rows, v_max, p_row_count, p_max_id;
  END IF;
  IF v_table = 'audit_log' AND p_platform_signature IS NOT NULL THEN
    EXECUTE format($q$
      INSERT INTO audit_chain_seals (tenant_id, month, last_id, last_hash, signature)
      SELECT '00000000-0000-0000-0000-000000000000', $1, p.id, p.row_hash, $2 FROM %I p
       WHERE p.tenant_id IS NULL ORDER BY p.id DESC LIMIT 1
      ON CONFLICT (tenant_id, month) DO NOTHING
    $q$, p_partition) USING v_month, p_platform_signature;
  END IF;
  EXECUTE format('SELECT string_agg(DISTINCT t.region_code, '','' ORDER BY t.region_code) FROM (SELECT DISTINCT tenant_id FROM %I) x JOIN tenants t ON t.id = x.tenant_id',
                 p_partition) INTO v_region;
  EXECUTE format('ALTER TABLE %I DETACH PARTITION %I', CASE v_table WHEN 'audit_log' THEN 'audit_log_months' ELSE v_table END, p_partition);
  EXECUTE format('DROP TABLE %I', p_partition);
  INSERT INTO archive_manifests (table_name, partition_name, month, region, object_key, row_count, sha256)
  VALUES (v_table, p_partition, v_month, COALESCE(v_region, '-'), p_object_key, p_row_count, p_sha256);
END $$;

-- Restore drill: load archived JSON lines into <partition>_restored (forced RLS, no app grant; never attached to the live
-- table). p_first starts a fresh copy.
CREATE FUNCTION audit_archive_restore_rows(p_partition text, p_rows text[], p_first boolean) RETURNS bigint
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_table text;
  v_to    text := p_partition || '_restored';
  n       bigint;
BEGIN
  SELECT table_name INTO v_table FROM archive_manifests WHERE partition_name = p_partition;
  IF v_table IS NULL THEN
    RAISE EXCEPTION 'OPS-068: % has no archive manifest', p_partition;
  END IF;
  IF p_first THEN
    EXECUTE format('DROP TABLE IF EXISTS %I', v_to);
    EXECUTE format('CREATE TABLE %I (LIKE %I)', v_to, v_table);
    PERFORM enforce_tenant_rls(v_to::regclass);
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
      EXECUTE format('REVOKE ALL ON %I FROM nabd_app', v_to);
    END IF;
  END IF;
  EXECUTE format('INSERT INTO %1$I SELECT (jsonb_populate_record(NULL::%1$I, l::jsonb)).* FROM unnest($1) l', v_to) USING p_rows;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- Ends the drill and drops the copy. Returns the row count and records restored_at when the copy holds the manifest's row
-- count and, for audit_log, every row still hashes to its own row_hash; returns NULL (with a warning) when it does not.
CREATE FUNCTION audit_archive_restore_finish(p_partition text) RETURNS bigint
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v    archive_manifests%ROWTYPE;
  v_to text := p_partition || '_restored';
  n    bigint;
  bad  bigint := 0;
BEGIN
  SELECT * INTO v FROM archive_manifests WHERE partition_name = p_partition;
  IF NOT FOUND OR to_regclass(v_to) IS NULL THEN
    RAISE EXCEPTION 'OPS-068: no restore of % is in progress', p_partition;
  END IF;
  EXECUTE format('SELECT count(*) FROM %I', v_to) INTO n;
  IF v.table_name = 'audit_log' THEN
    EXECUTE format($q$
      SELECT count(*) FROM %I r WHERE r.row_hash IS DISTINCT FROM CASE r.hash_v
        WHEN 1 THEN audit_row_hash(r.prev_hash, r.tenant_id, r.actor_type, r.actor_id, r.actor_name, r.actor_role, r.ip_address,
                                   r.action, r.entity_type, r.entity_id, r.before, r.after, r.created_at)
        WHEN 2 THEN audit_row_hash_v2(r.prev_hash, r.tenant_id, r.actor_type, r.actor_id, r.actor_name, r.actor_role, r.ip_address,
                                      r.action, r.entity_type, r.entity_id, r.before, r.after, r.created_at) END
    $q$, v_to) INTO bad;
  END IF;
  EXECUTE format('DROP TABLE %I', v_to);
  IF n <> v.row_count OR bad > 0 THEN
    RAISE WARNING 'OPS-068: restore of % does not match its manifest (% rows restored, % expected, % rows with a wrong hash)',
      p_partition, n, v.row_count, bad;
    RETURN NULL;
  END IF;
  UPDATE archive_manifests SET restored_at = now() WHERE partition_name = p_partition;
  RETURN n;
END $$;

REVOKE ALL ON FUNCTION audit_chain_anchor_ok(uuid, bigint, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_rows(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION phi_read_archive_bad_batches(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_heads(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_due(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_drop(text, bigint, bigint, text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_restore_rows(text, text[], boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_archive_restore_finish(text) FROM PUBLIC;
DO $$
DECLARE
  r record;
BEGIN
  -- the API role, and whoever may run search_audit_log (the platform console roles call verify_audit_chain for the platform chain)
  FOR r IN SELECT rolname FROM pg_roles WHERE rolname IN ('nabd_app', 'nabd_platform', 'nabd_control') LOOP
    EXECUTE format('GRANT EXECUTE ON FUNCTION audit_chain_anchor_ok(uuid, bigint, text, timestamptz) TO %I', r.rolname);
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION audit_archive_rows(text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION phi_read_archive_bad_batches(text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION audit_archive_heads(text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION audit_archive_due(text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION audit_archive_drop(text, bigint, bigint, text, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION audit_archive_restore_rows(text, text[], boolean) TO nabd_app;
    GRANT EXECUTE ON FUNCTION audit_archive_restore_finish(text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
