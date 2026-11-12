SET lock_timeout = '5s';
-- ADM-005: the control plane's activation funnel. One row per (clinic, wizard step), built from the internal outbox event
-- organization.setup_step.changed.v1 (SetupStepEvents) by platform.activation.ActivationEventHandler. Ids, step names, codes,
-- counters and timestamps only: no names, phones or patient counts. last_event_id = the outbox id of the newest event applied,
-- so a redelivered or late event never changes a row twice (idempotent replay).
CREATE TABLE master.activation_steps (
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  step            text NOT NULL CHECK (step ~ '^[a-z_]{1,32}$'),
  status          text NOT NULL CHECK (status IN ('reached','done','skipped','failed')),
  first_seen_at   timestamptz NOT NULL DEFAULT now(),
  completed_at    timestamptz,
  attempts        int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error_code text CHECK (last_error_code ~ '^[A-Z0-9_]{1,40}$'),
  nudges_sent     int NOT NULL DEFAULT 0 CHECK (nudges_sent >= 0),
  last_nudged_at  timestamptz,
  ticket_id       uuid,
  last_event_id   bigint NOT NULL DEFAULT 0,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, step)
);
CREATE INDEX idx_activation_steps_open ON master.activation_steps (first_seen_at, tenant_id, step) WHERE completed_at IS NULL;
-- Written in the clinic's tenant transaction (the outbox handler, the nudge put-back); forced RLS like V3061. The operator funnel,
-- the stuck list and the nudge claim cross clinics through the definers below (claim_due_plan_changes precedent).
SELECT enforce_tenant_rls('master.activation_steps');

-- Funnel: per sign-up week (clinic-independent ISO week of tenants.created_at) and step, how many clinics reached and left
-- (done or skipped) the step. Aggregates only.
CREATE FUNCTION activation_funnel(p_from timestamptz, p_to timestamptz, p_market text, p_specialty text)
RETURNS TABLE (week date, step text, reached bigint, completed bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT date_trunc('week', t.created_at)::date, a.step, count(*), count(a.completed_at)
  FROM master.activation_steps a JOIN tenants t ON t.id = a.tenant_id
  WHERE t.created_at >= p_from AND t.created_at < p_to
    AND (p_market IS NULL OR t.region_code = p_market)
    AND (p_specialty IS NULL OR p_specialty = ANY (t.specialties))
  GROUP BY 1, 2;
$$;

-- Stuck list: open steps first seen before p_before, of clinics not live yet, oldest first, keyset after (since, tenant, step).
CREATE FUNCTION activation_stuck(p_before timestamptz, p_after_since timestamptz, p_after_tenant uuid, p_after_step text, p_limit int)
RETURNS TABLE (tenant_id uuid, step text, since timestamptz, nudges_sent int)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT a.tenant_id, a.step, a.first_seen_at, a.nudges_sent
  FROM master.activation_steps a JOIN tenants t ON t.id = a.tenant_id
  WHERE a.completed_at IS NULL AND a.first_seen_at < p_before AND t.setup_completed_at IS NULL
    AND t.status IN ('provisioning','trialing','active')
    AND (p_after_since IS NULL OR (a.first_seen_at, a.tenant_id, a.step) > (p_after_since, p_after_tenant, p_after_step))
  ORDER BY a.first_seen_at, a.tenant_id, a.step
  LIMIT p_limit;
$$;

-- Nudge claim: stuck since p_stuck_before and not nudged since p_renudge_before (one nudge per clinic and step per 7 days).
-- Marks the claim at once (another replica skips locked rows); the job puts it back when the send fails.
CREATE FUNCTION claim_activation_nudges(p_stuck_before timestamptz, p_renudge_before timestamptz, p_limit int)
RETURNS TABLE (tenant_id uuid, step text, previous_nudged_at timestamptz)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  WITH due AS (
    SELECT a.tenant_id, a.step, a.last_nudged_at FROM master.activation_steps a JOIN tenants t ON t.id = a.tenant_id
    WHERE a.completed_at IS NULL AND a.first_seen_at <= p_stuck_before
      AND (a.last_nudged_at IS NULL OR a.last_nudged_at <= p_renudge_before)
      AND t.setup_completed_at IS NULL AND t.status IN ('provisioning','trialing','active')
    ORDER BY a.first_seen_at LIMIT p_limit
    FOR UPDATE OF a SKIP LOCKED)
  UPDATE master.activation_steps a SET last_nudged_at = now(), nudges_sent = a.nudges_sent + 1, updated_at = now()
  FROM due WHERE a.tenant_id = due.tenant_id AND a.step = due.step
  RETURNING a.tenant_id, a.step, due.last_nudged_at;
$$;

REVOKE ALL ON FUNCTION activation_funnel(timestamptz, timestamptz, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION activation_stuck(timestamptz, timestamptz, uuid, text, int) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_activation_nudges(timestamptz, timestamptz, int) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION activation_funnel(timestamptz, timestamptz, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION activation_stuck(timestamptz, timestamptz, uuid, text, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION claim_activation_nudges(timestamptz, timestamptz, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
