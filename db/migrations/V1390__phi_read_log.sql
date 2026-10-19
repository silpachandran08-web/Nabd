SET lock_timeout = '5s';
-- PLT-039 (MF-18, SEC-09, doc06 D8): who read which patient's record, kept apart from audit_log.
-- phi_read_log: append-only, monthly RANGE partitions on read_at (native partitioning; pg_partman is not installed,
-- so phi_read_log_ensure_partitions() creates the months ahead and PhiReadSealJob calls it). Identifiers only, never
-- clinical content. phi_read_batches: per-tenant chain, each batch_hash = sha256(prev_batch_hash || its rows).
-- Online retention: 13 months pending DPO confirmation; archiving is PLT-042.

CREATE TABLE phi_read_log (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id            uuid NOT NULL,
  read_at              timestamptz NOT NULL DEFAULT now(),
  actor_type           text NOT NULL CHECK (actor_type IN ('staff', 'operator', 'patient_link', 'system')),
  actor_id             uuid,
  session_id           uuid,
  patient_id           uuid NOT NULL,
  resource_type        text NOT NULL,
  resource_id          uuid,
  endpoint             text,
  purpose_code         text NOT NULL CHECK (purpose_code IN ('treatment', 'billing', 'support', 'break_glass', 'export', 'directory')),
  break_glass_grant_id uuid,
  ip                   inet,
  request_id           text,
  batch_id             bigint,
  PRIMARY KEY (tenant_id, read_at, id)
) PARTITION BY RANGE (read_at);
CREATE INDEX idx_phi_read_log_patient ON phi_read_log (tenant_id, patient_id, read_at DESC);
CREATE INDEX idx_phi_read_log_actor ON phi_read_log (tenant_id, actor_id, read_at DESC);
CREATE INDEX idx_phi_read_log_unsealed ON phi_read_log (tenant_id, id) WHERE batch_id IS NULL;
CREATE TABLE phi_read_log_default PARTITION OF phi_read_log DEFAULT;  -- never loses a read if a month was not created in time

CREATE TABLE phi_read_batches (
  tenant_id       uuid NOT NULL,
  batch_id        bigint NOT NULL,
  from_id         bigint NOT NULL,
  to_id           bigint NOT NULL,
  row_count       int NOT NULL,
  batch_hash      text NOT NULL,
  prev_batch_hash text NOT NULL,
  sealed_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, batch_id)
);

-- In-app owner alert for the read-volume rule; one per actor per clock hour.
CREATE TABLE phi_read_volume_alerts (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  actor_id          uuid NOT NULL,
  distinct_patients int NOT NULL,
  window_minutes    int NOT NULL,
  hour_bucket       timestamptz NOT NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, actor_id, hour_bucket)
);
CREATE INDEX idx_phi_read_volume_alerts_tenant ON phi_read_volume_alerts (tenant_id, created_at DESC);

-- Immutability: content never changes. The one permitted UPDATE is the sealer stamping batch_id NULL -> value.
CREATE FUNCTION phi_read_log_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.batch_id IS NULL AND NEW.batch_id IS NOT NULL
     AND (to_jsonb(NEW) - 'batch_id') = (to_jsonb(OLD) - 'batch_id') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION '% is not permitted on %: the PHI read log is append-only', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE FUNCTION phi_read_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'TRUNCATE is not permitted on %: the PHI read log is append-only', TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER trg_phi_read_log_immutable BEFORE UPDATE OR DELETE ON phi_read_log
  FOR EACH ROW EXECUTE FUNCTION phi_read_log_immutable();  -- row triggers are cloned onto every partition
CREATE TRIGGER trg_phi_read_log_no_truncate BEFORE TRUNCATE ON phi_read_log
  FOR EACH STATEMENT EXECUTE FUNCTION phi_read_no_truncate();
CREATE TRIGGER trg_phi_read_log_no_truncate BEFORE TRUNCATE ON phi_read_log_default
  FOR EACH STATEMENT EXECUTE FUNCTION phi_read_no_truncate();
CREATE FUNCTION phi_read_batches_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is not permitted on phi_read_batches: sealed batches are final', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER trg_phi_read_batches_immutable BEFORE UPDATE OR DELETE ON phi_read_batches
  FOR EACH ROW EXECUTE FUNCTION phi_read_batches_immutable();
CREATE TRIGGER trg_phi_read_batches_no_truncate BEFORE TRUNCATE ON phi_read_batches
  FOR EACH STATEMENT EXECUTE FUNCTION phi_read_no_truncate();

SELECT enforce_tenant_rls('phi_read_log');
SELECT enforce_tenant_rls('phi_read_log_default');
SELECT enforce_tenant_rls('phi_read_batches');
SELECT enforce_tenant_rls('phi_read_volume_alerts');

-- The app inserts and reads; it never updates, deletes or touches partitions directly (reads go through the parent).
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE UPDATE, DELETE ON phi_read_log FROM nabd_app;
    REVOKE ALL ON phi_read_log_default FROM nabd_app;
    REVOKE INSERT, UPDATE, DELETE ON phi_read_batches FROM nabd_app;
  END IF;
  -- Creating a partition requires owning the parent, and the runtime caller is the definer function below.
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    ALTER TABLE phi_read_log OWNER TO nabd_definer;
  END IF;
END $$;

-- Monthly partitions from p_from's month to p_months_ahead months past now(). Idempotent; partitions get forced RLS
-- (no app grant: rows are reached only through the parent) and the TRUNCATE guard (statement triggers are not cloned).
CREATE FUNCTION phi_read_log_ensure_partitions(p_from date, p_months_ahead int) RETURNS int
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  m date := date_trunc('month', p_from)::date;
  stop date := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => GREATEST(p_months_ahead, 0)))::date;
  part text;
  n int := 0;
BEGIN
  WHILE m <= stop LOOP
    part := format('phi_read_log_y%sm%s', to_char(m, 'YYYY'), to_char(m, 'MM'));
    IF to_regclass(part) IS NULL THEN
      -- UTC month boundaries, whatever the session TimeZone
      EXECUTE format('CREATE TABLE %I PARTITION OF phi_read_log FOR VALUES FROM (%L) TO (%L)', part,
                     m::text || ' 00:00:00+00', (m + interval '1 month')::date::text || ' 00:00:00+00');
      PERFORM enforce_tenant_rls(part::regclass);
      IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
        EXECUTE format('REVOKE ALL ON %I FROM nabd_app', part);
      END IF;
      EXECUTE format('CREATE TRIGGER trg_phi_read_log_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION phi_read_no_truncate()', part);
      n := n + 1;
    END IF;
    m := (m + interval '1 month')::date;
  END LOOP;
  RETURN n;
END $$;

-- Seals every tenant's unsealed rows older than the current minute into one batch per tenant, chained to the
-- tenant's previous batch. The UPDATE ... RETURNING set is exactly what gets hashed, so a late-committing row is
-- never stamped without being hashed; it simply lands in the next batch.
CREATE FUNCTION seal_phi_read_batches() RETURNS int
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  cutoff timestamptz := date_trunc('minute', now());
  t uuid;
  bid bigint;
  prev text;
  f bigint; l bigint; c int; h text;
  n int := 0;
BEGIN
  FOR t IN SELECT DISTINCT tenant_id FROM phi_read_log WHERE batch_id IS NULL AND read_at < cutoff LOOP
    PERFORM pg_advisory_xact_lock(hashtext('phi_read_seal'), hashtext(t::text));
    SELECT b.batch_id, b.batch_hash INTO bid, prev FROM phi_read_batches b WHERE b.tenant_id = t ORDER BY b.batch_id DESC LIMIT 1;
    bid := COALESCE(bid, 0) + 1;
    prev := COALESCE(prev, 'genesis');
    WITH s AS (
      UPDATE phi_read_log SET batch_id = bid WHERE tenant_id = t AND batch_id IS NULL AND read_at < cutoff RETURNING *
    )
    SELECT min(s.id), max(s.id), count(*)::int,
           encode(sha256(convert_to(prev || E'\n' || string_agg(phi_read_row_text(s.id, s.read_at, s.actor_type, s.actor_id, s.session_id,
                 s.patient_id, s.resource_type, s.resource_id, s.endpoint, s.purpose_code, s.break_glass_grant_id, s.ip, s.request_id),
                 E'\n' ORDER BY s.id), 'UTF8')), 'hex')
      INTO f, l, c, h FROM s;
    IF c > 0 THEN
      INSERT INTO phi_read_batches (tenant_id, batch_id, from_id, to_id, row_count, batch_hash, prev_batch_hash)
      VALUES (t, bid, f, l, c, h, prev);
      n := n + 1;
    END IF;
  END LOOP;
  RETURN n;
END $$;

-- Canonical row text for the batch hash: timezone-independent (epoch microseconds), NULLs as empty fields.
CREATE FUNCTION phi_read_row_text(p_id bigint, p_read_at timestamptz, p_actor_type text, p_actor_id uuid, p_session_id uuid,
                                  p_patient_id uuid, p_resource_type text, p_resource_id uuid, p_endpoint text, p_purpose text,
                                  p_bg uuid, p_ip inet, p_request_id text) RETURNS text
  LANGUAGE sql IMMUTABLE AS $$
  SELECT concat_ws('|', p_id, (extract(epoch FROM p_read_at) * 1000000)::bigint, p_actor_type, COALESCE(p_actor_id::text, ''),
                   COALESCE(p_session_id::text, ''), p_patient_id, p_resource_type, COALESCE(p_resource_id::text, ''),
                   COALESCE(p_endpoint, ''), p_purpose, COALESCE(p_bg::text, ''), COALESCE(host(p_ip), ''), COALESCE(p_request_id, ''))
$$;

REVOKE ALL ON FUNCTION phi_read_log_ensure_partitions(date, int) FROM PUBLIC;
REVOKE ALL ON FUNCTION seal_phi_read_batches() FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION phi_read_log_ensure_partitions(date, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION seal_phi_read_batches() TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();

SELECT phi_read_log_ensure_partitions(((now() AT TIME ZONE 'UTC') - interval '1 month')::date, 3);
