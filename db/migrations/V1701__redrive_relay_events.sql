SET lock_timeout = '5s';
-- OPS-016 (FIX-C, QA-7 D2): redrive. The relay marks a row done once its XADD succeeded; if Valkey then loses the
-- entry (restart without persistence, failover with asynchronous replication) the event would be gone. Done rows stay
-- 7 days (OutboxPurgeJob), so on-call puts the domain rows finished in a time window back to pending and the relay
-- XADDs them again; consumers dedupe on the CloudEvents id (events.md section 8, "Redrive"). Cross-tenant on purpose,
-- SECURITY DEFINER like every outbox housekeeping function. Returns the number of rows put back.
CREATE FUNCTION redrive_relay_events(p_from timestamptz, p_to timestamptz)
RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH r AS (
    UPDATE outbox_events
    SET status = 'pending', next_attempt_at = now(), locked_until = NULL
    WHERE ce_type IS NOT NULL AND status = 'done' AND updated_at >= p_from AND updated_at < p_to
    RETURNING 1
  )
  SELECT count(*)::int FROM r
$$;
REVOKE ALL ON FUNCTION redrive_relay_events(timestamptz, timestamptz) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION redrive_relay_events(timestamptz, timestamptz) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
