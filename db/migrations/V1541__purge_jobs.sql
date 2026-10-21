-- OPS-013: batched retention purges for the growth tables. All of them are RLS-forced (or, login_attempts, forced by
-- V211) and the API runs as nabd_app, so each cross-tenant delete goes through a narrow SECURITY DEFINER function that
-- returns only a count (purge_outbox_events, V1320, is the precedent and stays the outbox purge).
-- Each call deletes at most p_batch rows: SKIP LOCKED never waits on a row a request holds, and the function-level
-- lock_timeout = 1s caps any table-lock wait, so a purge never queues behind (or in front of) OPD traffic for > 1 s.
SET lock_timeout = '5s';

-- sessions: 30 days (p_days) after the session expired; a revocation later than the expiry pushes it out too.
CREATE FUNCTION purge_sessions(p_days int, p_batch int) RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public SET lock_timeout = '1s'
AS $$
  WITH d AS (
    DELETE FROM sessions WHERE id IN (
      SELECT id FROM sessions
      WHERE expires_at < now() - make_interval(days => p_days)
        AND (revoked_at IS NULL OR revoked_at < now() - make_interval(days => p_days))
      LIMIT p_batch FOR UPDATE SKIP LOCKED)
    RETURNING 1)
  SELECT count(*)::int FROM d
$$;
REVOKE ALL ON FUNCTION purge_sessions(int, int) FROM PUBLIC;

CREATE FUNCTION purge_login_attempts(p_days int, p_batch int) RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public SET lock_timeout = '1s'
AS $$
  WITH d AS (
    DELETE FROM login_attempts WHERE id IN (
      SELECT id FROM login_attempts WHERE attempted_at < now() - make_interval(days => p_days)
      LIMIT p_batch FOR UPDATE SKIP LOCKED)
    RETURNING 1)
  SELECT count(*)::int FROM d
$$;
REVOKE ALL ON FUNCTION purge_login_attempts(int, int) FROM PUBLIC;

CREATE FUNCTION purge_whatsapp_messages(p_months int, p_batch int) RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public SET lock_timeout = '1s'
AS $$
  WITH d AS (
    DELETE FROM whatsapp_messages WHERE id IN (
      SELECT id FROM whatsapp_messages WHERE created_at < now() - make_interval(months => p_months)
      LIMIT p_batch FOR UPDATE SKIP LOCKED)
    RETURNING 1)
  SELECT count(*)::int FROM d
$$;
REVOKE ALL ON FUNCTION purge_whatsapp_messages(int, int) FROM PUBLIC;

-- Supporting indexes for the purge predicates. whatsapp_messages is indexed on created_at alone (not tenant_id first)
-- because the purge is one cross-tenant range.
-- shortcut: plain CREATE INDEX (Flyway runs this in a transaction, so no CONCURRENTLY; V150/V311 precedent); on a
-- large prod table run each by hand with CONCURRENTLY first, IF NOT EXISTS makes this a no-op afterwards.
-- migration-lint: allow MIG-001 CONCURRENTLY cannot run in the Flyway transaction; see the shortcut above
CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions (expires_at);
CREATE INDEX IF NOT EXISTS idx_login_attempts_attempted_at ON login_attempts (attempted_at);
CREATE INDEX IF NOT EXISTS idx_whatsapp_messages_created_at ON whatsapp_messages (created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_events_finished ON outbox_events (updated_at) WHERE status IN ('done', 'dead');

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION purge_sessions(int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION purge_login_attempts(int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION purge_whatsapp_messages(int, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
