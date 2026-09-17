-- NB-007: transactional outbox — the durable queue every clinical/billing/messaging event that
-- must never be lost goes through (drives WhatsApp, SMS, recall, webhooks once E17 wires a
-- consumer; see EventPublisher/OutboxEventHandler). RLS-protected per tenant like every other
-- business table, same rationale as audit_log (V14) — payloads can carry tenant PHI/PII, so this
-- is public schema, not master (contrast provisioning_jobs, which is platform-operator-only data
-- and carries no RLS). The one place that legitimately needs to see every tenant's due events at
-- once is the dispatcher's poll loop, so — same precedent as search_audit_log (V16) —
-- claim_outbox_events() below is a narrow, explicit SECURITY DEFINER escape hatch for exactly that
-- one cross-tenant operation. Once an event is claimed, the dispatcher sets app.tenant_id to that
-- event's own tenant before doing anything else (same as ProvisioningStepRunner per step), so
-- marking it done/failed/dead is an ordinary RLS-scoped UPDATE — no further escape hatch needed.
CREATE TABLE outbox_events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  event_type      text NOT NULL,
  payload         jsonb NOT NULL,
  -- pending: ready (or waiting out backoff) to be claimed. processing: claimed, lease held until
  -- locked_until. done: delivered. dead: exhausted max attempts (OutboxEventProcessor's fixed
  -- policy — deliberately not columns here, NB-007 ships one policy for every event type).
  status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'done', 'dead')),
  -- Bumped by claim_outbox_events on every claim, including a reclaim after an expired lease — a
  -- handler that reliably crashes the whole JVM must still eventually dead-letter, not retry
  -- forever, so this counts delivery *attempts*, not handler *failures*.
  attempts        int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  locked_until    timestamptz,
  last_error      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_outbox_events_updated_at BEFORE UPDATE ON outbox_events
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ponytail: covers the common (pending) branch of the claim query only; add a partial index on
-- locked_until if the processing/expired-lease branch ever shows up in slow query logs.
CREATE INDEX idx_outbox_events_claimable ON outbox_events (next_attempt_at)
  WHERE status IN ('pending', 'processing');

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox_events
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Atomically claims up to p_limit due events across every tenant — pending ones whose backoff has
-- elapsed, or processing ones whose lease expired (a dispatcher that died before finishing —
-- exactly this ticket's acceptance criterion). FOR UPDATE SKIP LOCKED means two dispatcher
-- instances polling concurrently can never claim the same row twice — scales to multiple
-- instances with no extra coordination.
CREATE FUNCTION claim_outbox_events(p_limit int, p_lease_seconds int)
RETURNS SETOF outbox_events
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE outbox_events
  SET status = 'processing',
      attempts = attempts + 1,
      locked_until = now() + make_interval(secs => p_lease_seconds)
  WHERE id IN (
    SELECT id FROM outbox_events
    WHERE (status = 'pending' AND next_attempt_at <= now())
       OR (status = 'processing' AND locked_until < now())
    ORDER BY next_attempt_at
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED
  )
  RETURNING *;
$$;
REVOKE ALL ON FUNCTION claim_outbox_events(int, int) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION claim_outbox_events(int,int) TO <app_role>;
