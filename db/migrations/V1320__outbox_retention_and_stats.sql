-- OPS-014: outbox lease renewal, retention and metrics. outbox_events is RLS-forced and the API runs as nabd_app, so both
-- cross-tenant housekeeping reads/writes go through narrow SECURITY DEFINER functions (claim_outbox_events, V44,
-- is the precedent). Neither returns payloads: purge returns a count, stats returns counts per region.
SET lock_timeout = '5s';

-- Deletes at most p_batch finished rows (done older than p_done_days, dead older than p_dead_days, by updated_at,
-- which is when the row was finished). OutboxPurgeJob loops until a batch comes back short.
-- shortcut: no index on (status, updated_at); LIMIT without ORDER BY stops at the first p_batch matches and the
-- oldest rows sit first in heap order, so this stays cheap while the purge keeps the table small. Add a partial
-- index if pg_stat_statements shows this function scanning.
CREATE FUNCTION purge_outbox_events(p_done_days int, p_dead_days int, p_batch int) RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH d AS (
    DELETE FROM outbox_events WHERE id IN (
      SELECT id FROM outbox_events
      WHERE (status = 'done' AND updated_at < now() - make_interval(days => p_done_days))
         OR (status = 'dead' AND updated_at < now() - make_interval(days => p_dead_days))
      LIMIT p_batch)
    RETURNING 1)
  SELECT count(*)::int FROM d
$$;
REVOKE ALL ON FUNCTION purge_outbox_events(int, int, int) FROM PUBLIC;

-- Renews the lease of the dispatcher's not-yet-processed claimed events (OutboxDispatcher calls it before each
-- event, with the rest of its batch), so events waiting behind a slow provider never expire and get reclaimed.
-- Fenced like every completion: only rows still 'processing' at the attempts value this worker claimed. Returns
-- the ids still owned; an id missing from the result was reclaimed by another worker and must be skipped.
-- Cross-tenant because one claim spans tenants (same precedent as claim_outbox_events).
CREATE FUNCTION extend_outbox_leases(p_ids bigint[], p_attempts int[], p_lease_seconds int) RETURNS SETOF bigint
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  UPDATE outbox_events o SET locked_until = now() + make_interval(secs => p_lease_seconds)
  FROM unnest(p_ids, p_attempts) AS c(id, attempts)
  WHERE o.id = c.id AND o.attempts = c.attempts AND o.status = 'processing'
  RETURNING o.id
$$;
REVOKE ALL ON FUNCTION extend_outbox_leases(bigint[], int[], int) FROM PUBLIC;

-- One cheap aggregate for the metrics gauges: one row per region. No tenant column at all: OPS-025 keeps tenant
-- and patient identifiers out of /actuator/prometheus, and region (IN, KSA) is bounded cardinality.
-- pending counts 'processing' too (claimed, not finished); age is from created_at of the oldest unfinished row.
CREATE FUNCTION outbox_stats()
RETURNS TABLE (region text, pending bigint, dead bigint, oldest_pending_age_seconds double precision)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT r.region,
         count(o.id) FILTER (WHERE o.status IN ('pending', 'processing')),
         count(o.id) FILTER (WHERE o.status = 'dead'),
         COALESCE(EXTRACT(EPOCH FROM now() - min(o.created_at) FILTER (WHERE o.status IN ('pending', 'processing'))), 0)::double precision
  FROM (VALUES ('IN'), ('KSA')) AS r(region)
  LEFT JOIN tenants t ON t.region = r.region
  LEFT JOIN outbox_events o ON o.tenant_id = t.id AND o.status <> 'done'
  GROUP BY r.region
$$;
REVOKE ALL ON FUNCTION outbox_stats() FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION purge_outbox_events(int, int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION outbox_stats() TO nabd_app;
    GRANT EXECUTE ON FUNCTION extend_outbox_leases(bigint[], int[], int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
