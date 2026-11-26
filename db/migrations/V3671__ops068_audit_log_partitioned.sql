SET lock_timeout = '5s';
-- OPS-068 (DP-11, MF-65, SEC-28): audit_log becomes monthly RANGE (created_at) partitions; sealed months can be archived.
-- Design, lock cost, online conversion plan and rollback: docs/architecture/audit-partitioning.md.
--
-- Shape:  audit_log (parent, owner nabd_migrator, PK (id, created_at), same columns, checks, FK, indexes, triggers, RLS, grants)
--           audit_log_months   one partition MINVALUE..MAXVALUE, itself RANGE (created_at); owner nabd_definer, so the
--                              SECURITY DEFINER functions below can add and detach months without owning the parent
--             audit_log_yYYYYmMM   one per UTC month, created 3 months ahead by audit_log_ensure_partitions()
--             audit_log_default    never loses a row if a month was not created in time
-- Native partitioning, not pg_partman: the extension is not in the test image (postgres:16-alpine), same as phi_read_log (V1390).
-- phi_read_log is NOT touched: V1390 already made it monthly RANGE (read_at) with PK (tenant_id, read_at, id).
--
-- Cost: ONE transaction under ACCESS EXCLUSIVE on audit_log (every audited write waits) that copies every row. Fine for a small
-- table; a table over 1 GB is refused: run the online copy-and-swap of the design document first, after which this migration
-- finds audit_log already partitioned and only installs the functions.
-- The old table is kept as audit_log_unpartitioned (no app grant) for rollback; db/deferred/M2__drop_audit_log_unpartitioned.sql drops it.

-- The monthly partitions of p_from's month and the p_months months after it (the daily job passes today and 3). Idempotent.
-- A month is built as a standalone table and ATTACHed: that takes SHARE UPDATE EXCLUSIVE on audit_log_months, so audited
-- writes do not wait (CREATE TABLE ... PARTITION OF would take ACCESS EXCLUSIVE). A month that was archived is not created
-- again; a month whose rows already sit in the default partition is skipped with a warning (design document, "default partition").
CREATE FUNCTION audit_log_ensure_partitions(p_from date, p_months int) RETURNS int
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public SET lock_timeout = '5s' AS $$
DECLARE
  m    date := date_trunc('month', p_from)::date;
  stop date := (date_trunc('month', p_from) + make_interval(months => GREATEST(p_months, 0)))::date;
  part text;
  lo   timestamptz;
  hi   timestamptz;
  ix   record;
  n    int := 0;
BEGIN
  WHILE m <= stop LOOP
    part := 'audit_log_y' || to_char(m, 'YYYY') || 'm' || to_char(m, 'MM');
    lo := (m::text || ' 00:00:00+00')::timestamptz;  -- UTC month boundaries, whatever the session TimeZone
    hi := ((m + interval '1 month')::date::text || ' 00:00:00+00')::timestamptz;
    IF to_regclass(part) IS NOT NULL
       OR EXISTS (SELECT 1 FROM archive_manifests WHERE table_name = 'audit_log' AND partition_name = part) THEN
      NULL;
    ELSIF EXISTS (SELECT 1 FROM audit_log_default WHERE created_at >= lo AND created_at < hi) THEN
      RAISE WARNING 'OPS-068: % not created, audit_log_default already holds rows of that month', part;
    ELSE
      EXECUTE format('CREATE TABLE %I (LIKE audit_log_months INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', part);
      PERFORM enforce_tenant_rls(part::regclass);
      IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
        EXECUTE format('REVOKE ALL ON %I FROM nabd_app', part);  -- rows are reached only through audit_log
      END IF;
      -- statement triggers are not cloned onto partitions; the row triggers (chain, no update, no delete) are
      EXECUTE format('CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable()', part);
      EXECUTE format('ALTER TABLE audit_log_months ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)', part, lo, hi);
      -- idx_audit_log_tenant_id -> idx_audit_log_tenant_id_y2026m11: plans and the OPS-001 plan pins name the parent's index
      FOR ix IN SELECT c.relname AS leaf, r.relname AS root
                  FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                  JOIN pg_inherits h1 ON h1.inhrelid = i.indexrelid
                  JOIN pg_inherits h2 ON h2.inhrelid = h1.inhparent
                  JOIN pg_class r ON r.oid = h2.inhparent
                 WHERE i.indrelid = part::regclass
      LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I', ix.leaf, ix.root || substr(part, length('audit_log') + 1));
      END LOOP;
      n := n + 1;
    END IF;
    m := (m + interval '1 month')::date;
  END LOOP;
  RETURN n;
END $$;

DO $$
DECLARE
  trigger_defs text[];
  seq_last     bigint;
  seq_called   boolean;
  first_month  date;
  n_old        bigint;
  n_new        bigint;
  g            record;
  t            text;
  to_definer   boolean := EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER');
BEGIN
  IF (SELECT relkind FROM pg_class WHERE oid = 'public.audit_log'::regclass) = 'p' THEN
    RAISE NOTICE 'OPS-068: audit_log is already partitioned (online conversion); nothing to convert';
    RETURN;
  END IF;
  IF pg_total_relation_size('public.audit_log') > 1073741824 THEN
    RAISE EXCEPTION 'OPS-068: audit_log is % : too large to rewrite inside a migration. Run the online conversion in docs/architecture/audit-partitioning.md first.',
      pg_size_pretty(pg_total_relation_size('public.audit_log'));
  END IF;

  LOCK TABLE audit_log IN ACCESS EXCLUSIVE MODE;  -- no audit insert may land between the copy and the swap
  SELECT array_agg(pg_get_triggerdef(oid) ORDER BY tgname) INTO trigger_defs
    FROM pg_trigger WHERE tgrelid = 'public.audit_log'::regclass AND NOT tgisinternal;  -- keeps the ADM-016 WHEN clause as it is
  SELECT last_value, is_called INTO seq_last, seq_called FROM audit_log_id_seq;

  ALTER TABLE audit_log RENAME TO audit_log_unpartitioned;
  ALTER SEQUENCE audit_log_id_seq RENAME TO audit_log_unpartitioned_id_seq;
  ALTER INDEX audit_log_pkey RENAME TO audit_log_unpartitioned_pkey;
  ALTER INDEX idx_audit_log_tenant_created RENAME TO idx_audit_log_unpartitioned_tenant_created;
  ALTER INDEX idx_audit_log_entity RENAME TO idx_audit_log_unpartitioned_entity;
  ALTER INDEX idx_audit_log_tenant_id RENAME TO idx_audit_log_unpartitioned_tenant_id;
  ALTER INDEX idx_audit_log_platform RENAME TO idx_audit_log_unpartitioned_platform;

  -- Same columns in the same order (V14, V126, V1380). id alone is no longer unique by constraint: a partitioned table's
  -- unique index must contain the partition key. Ids still come from one sequence, taken under the chain head's lock.
  CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id   uuid REFERENCES tenants(id),
    actor_type  text NOT NULL CHECK (actor_type IN ('staff', 'operator', 'system')),
    actor_id    uuid,
    actor_name  text NOT NULL,
    actor_role  text NOT NULL,
    ip_address  inet,
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   uuid,
    before      jsonb,
    after       jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    prev_hash   text,
    row_hash    text NOT NULL,
    hash_v      smallint NOT NULL DEFAULT 2,
    PRIMARY KEY (id, created_at),
    CONSTRAINT audit_log_platform_rows_are_operator_or_system CHECK (tenant_id IS NOT NULL OR actor_type IN ('operator', 'system'))
  ) PARTITION BY RANGE (created_at);
  CREATE INDEX idx_audit_log_tenant_created ON audit_log (tenant_id, created_at DESC);
  CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
  CREATE INDEX idx_audit_log_tenant_id ON audit_log (tenant_id, id DESC);
  CREATE INDEX idx_audit_log_platform ON audit_log (id) WHERE tenant_id IS NULL;

  CREATE TABLE audit_log_months PARTITION OF audit_log FOR VALUES FROM (MINVALUE) TO (MAXVALUE) PARTITION BY RANGE (created_at);
  CREATE TABLE audit_log_default PARTITION OF audit_log_months DEFAULT;
  ALTER TABLE audit_log_unpartitioned NO FORCE ROW LEVEL SECURITY;  -- the migrator owns it but has no BYPASSRLS: let the copy see every row
  -- every month that holds rows, the current month and the next three
  SELECT COALESCE(min(created_at AT TIME ZONE 'UTC'), now() AT TIME ZONE 'UTC')::date INTO first_month FROM audit_log_unpartitioned;
  PERFORM audit_log_ensure_partitions(first_month, 3 + (extract(year FROM age(date_trunc('month', now() AT TIME ZONE 'UTC'), date_trunc('month', first_month))) * 12
                                                        + extract(month FROM age(date_trunc('month', now() AT TIME ZONE 'UTC'), date_trunc('month', first_month))))::int);

  -- Rows are copied as they are (ids, hashes, hash_v): the chain trigger is not on the new table yet.
  INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id,
                         before, after, created_at, prev_hash, row_hash, hash_v) OVERRIDING SYSTEM VALUE
  SELECT id, tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id,
         before, after, created_at, prev_hash, row_hash, hash_v FROM audit_log_unpartitioned ORDER BY id;
  SELECT count(*) INTO n_old FROM audit_log_unpartitioned;
  SELECT count(*) INTO n_new FROM audit_log;
  IF n_old <> n_new OR EXISTS (SELECT o.id FROM audit_log_unpartitioned o LEFT JOIN audit_log n ON n.id = o.id AND n.created_at = o.created_at
                                WHERE n.id IS NULL OR n.row_hash IS DISTINCT FROM o.row_hash OR n.prev_hash IS DISTINCT FROM o.prev_hash) THEN
    RAISE EXCEPTION 'OPS-068: copy check failed (% rows before, % after)', n_old, n_new;
  END IF;
  ALTER TABLE audit_log_unpartitioned FORCE ROW LEVEL SECURITY;
  PERFORM setval(pg_get_serial_sequence('public.audit_log', 'id'), seq_last, seq_called);

  -- Triggers exactly as they were. Row triggers are cloned onto every partition, present and future.
  FOREACH t IN ARRAY trigger_defs LOOP
    EXECUTE t;
  END LOOP;
  CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log_months FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();
  CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log_default FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();

  PERFORM enforce_tenant_rls('audit_log');  -- the same four per-command policies the old table has (V211, V2521)
  PERFORM enforce_tenant_rls('audit_log_months');
  PERFORM enforce_tenant_rls('audit_log_default');

  -- Grants on the parent: exactly the old table's. Then the old table and the partitions are closed to the API role.
  FOR g IN SELECT a.grantee::regrole::text AS who, a.privilege_type AS priv FROM pg_class k, aclexplode(k.relacl) a
            WHERE k.oid = 'public.audit_log'::regclass AND a.grantee <> k.relowner
           EXCEPT
           SELECT a.grantee::regrole::text, a.privilege_type FROM pg_class k, aclexplode(k.relacl) a
            WHERE k.oid = 'public.audit_log_unpartitioned'::regclass
  LOOP
    EXECUTE format('REVOKE %s ON audit_log FROM %s', g.priv, g.who);
  END LOOP;
  FOR g IN SELECT a.grantee::regrole::text AS who, a.privilege_type AS priv FROM pg_class k, aclexplode(k.relacl) a
            WHERE k.oid = 'public.audit_log_unpartitioned'::regclass AND a.grantee <> k.relowner
  LOOP
    EXECUTE format('GRANT %s ON audit_log TO %s', g.priv, g.who);
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON audit_log_unpartitioned, audit_log_months, audit_log_default FROM nabd_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_reports') THEN
    REVOKE ALL ON audit_log_unpartitioned FROM nabd_reports;
  END IF;

  -- Adding or detaching a month needs the owner of its direct parent; the runtime caller is a SECURITY DEFINER function.
  IF to_definer THEN
    FOR t IN SELECT c.relname FROM pg_class c
              WHERE c.oid = 'public.audit_log_months'::regclass
                 OR c.oid IN (SELECT inhrelid FROM pg_inherits WHERE inhparent = 'public.audit_log_months'::regclass)
    LOOP
      EXECUTE format('ALTER TABLE %I OWNER TO nabd_definer', t);
    END LOOP;
  END IF;
END $$;

REVOKE ALL ON FUNCTION audit_log_ensure_partitions(date, int) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION audit_log_ensure_partitions(date, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
