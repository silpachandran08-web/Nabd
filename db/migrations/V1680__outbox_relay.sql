-- OPS-016: the Valkey Streams relay (docs/architecture/events.md section 7). Domain events (ce_type NOT NULL) and
-- legacy events (whatsapp.send, otp ...) are split between two owners so the relay and OutboxDispatcher can never
-- finish the same row: the relay claims only domain rows, and a dispatcher in a region with a relay claims only
-- legacy rows. Same claim/lease/fencing rules as claim_outbox_events (V44) and extend_outbox_leases (V1320).
-- Cross-tenant on purpose (one relay per region database), SECURITY DEFINER like every outbox housekeeping function.
SET lock_timeout = '5s';

-- The dispatcher's claim. p_skip_domain = true (Valkey configured in this region: the relay owns domain rows) claims
-- legacy rows only; false is exactly the V44 two-argument claim, which stays for callers that still use it.
CREATE FUNCTION claim_outbox_events(p_limit int, p_lease_seconds int, p_skip_domain boolean)
RETURNS SETOF outbox_events
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  UPDATE outbox_events
  SET status = 'processing',
      attempts = attempts + 1,
      locked_until = now() + make_interval(secs => p_lease_seconds)
  WHERE id IN (
    SELECT id FROM outbox_events
    WHERE ((status = 'pending' AND next_attempt_at <= now())
        OR (status = 'processing' AND locked_until < now()))
      AND (NOT p_skip_domain OR ce_type IS NULL)
    ORDER BY next_attempt_at
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED
  )
  RETURNING *;
$$;
REVOKE ALL ON FUNCTION claim_outbox_events(int, int, boolean) FROM PUBLIC;

-- The relay's claim: due domain rows only, with the envelope rendered by outbox_cloudevents (the one rendering, V1550)
-- and the target stream ev:<region>:<domain>. Returns no other column of the row.
CREATE FUNCTION claim_relay_events(p_limit int, p_lease_seconds int)
RETURNS TABLE (id bigint, attempts int, stream text, envelope text)
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH c AS (
    UPDATE outbox_events
    SET status = 'processing',
        attempts = attempts + 1,
        locked_until = now() + make_interval(secs => p_lease_seconds)
    WHERE outbox_events.id IN (
      SELECT o.id FROM outbox_events o
      WHERE o.ce_type IS NOT NULL
        AND ((o.status = 'pending' AND o.next_attempt_at <= now())
          OR (o.status = 'processing' AND o.locked_until < now()))
      ORDER BY o.next_attempt_at, o.id
      LIMIT p_limit
      FOR UPDATE SKIP LOCKED
    )
    RETURNING outbox_events.id, outbox_events.attempts
  )
  SELECT c.id, c.attempts,
         'ev:' || (v.envelope ->> 'region') || ':' || split_part(v.envelope ->> 'type', '.', 1),
         v.envelope::text
  FROM c JOIN outbox_cloudevents v ON v.id = c.id
  ORDER BY c.id
$$;
REVOKE ALL ON FUNCTION claim_relay_events(int, int) FROM PUBLIC;

-- Finishes a relay batch, fenced (status 'processing' at the attempts value claimed): p_done = true only after a
-- successful XADD; false puts the rows back to pending at once (Valkey down: they stay in Postgres). Returns the ids
-- updated; a missing id was reclaimed by another relay and is not touched.
CREATE FUNCTION finish_relay_events(p_ids bigint[], p_attempts int[], p_done boolean, p_error text)
RETURNS SETOF bigint
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  UPDATE outbox_events o
  SET status = CASE WHEN p_done THEN 'done' ELSE 'pending' END,
      next_attempt_at = CASE WHEN p_done THEN o.next_attempt_at ELSE now() END,
      last_error = CASE WHEN p_done THEN o.last_error ELSE left(p_error, 500) END
  FROM unnest(p_ids, p_attempts) AS c(id, attempts)
  WHERE o.id = c.id AND o.attempts = c.attempts AND o.status = 'processing' AND o.ce_type IS NOT NULL
  RETURNING o.id
$$;
REVOKE ALL ON FUNCTION finish_relay_events(bigint[], int[], boolean, text) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION claim_outbox_events(int, int, boolean) TO nabd_app;
    GRANT EXECUTE ON FUNCTION claim_relay_events(int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION finish_relay_events(bigint[], int[], boolean, text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
