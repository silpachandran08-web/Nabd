SET lock_timeout = '5s';
-- COM-004: the one notification contract. A module's request() writes a row here (render params encrypted) and a
-- comms.notification.requested.v1 event with ids only; the consumer fetches the params at send time (claim check).
CREATE TABLE notification_requests (
  id                   uuid PRIMARY KEY,
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  purpose              text NOT NULL,
  subject_type         text,
  subject_id           uuid,
  recipient_patient_id uuid,
  params_enc           bytea,
  language             text NOT NULL DEFAULT 'en',
  priority             text NOT NULL CHECK (priority IN ('OTP', 'TRANSACTIONAL', 'REMINDER', 'BULK')),
  channel_policy       text NOT NULL CHECK (channel_policy IN ('WHATSAPP_THEN_SMS', 'WHATSAPP_ONLY', 'SMS_ONLY', 'EMAIL')),
  not_before           timestamptz,
  not_after            timestamptz,
  dedupe_key           text NOT NULL,
  -- 'dispatched' (beyond the spec's three): the in-process consumer handed it to the channel, so a redelivery is a no-op
  status               text NOT NULL DEFAULT 'requested' CHECK (status IN ('requested', 'blocked', 'cancelled', 'dispatched')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, dedupe_key)
);
CREATE INDEX idx_notification_requests_subject ON notification_requests (tenant_id, subject_type, subject_id);
CREATE INDEX idx_notification_requests_params ON notification_requests ((coalesce(not_after, created_at))) WHERE params_enc IS NOT NULL;

SELECT enforce_tenant_rls('notification_requests');

-- The claim-check endpoint is called by a service, not a tenant user: it knows only the request id, so this returns the
-- row's tenant (and nothing else) for the caller to bind before reading the row under RLS.
CREATE FUNCTION notification_request_tenant(p_id uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT tenant_id FROM notification_requests WHERE id = p_id $$;
REVOKE ALL ON FUNCTION notification_request_tenant(uuid) FROM PUBLIC;

-- Render params are purged p_days after the send window closed (created_at when there is no window end).
CREATE FUNCTION purge_notification_params(p_days int, p_batch int) RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public SET lock_timeout = '1s'
AS $$
  WITH d AS (
    UPDATE notification_requests SET params_enc = NULL WHERE id IN (
      SELECT id FROM notification_requests
      WHERE params_enc IS NOT NULL AND coalesce(not_after, created_at) < now() - make_interval(days => p_days)
      LIMIT p_batch FOR UPDATE SKIP LOCKED)
    RETURNING 1)
  SELECT count(*)::int FROM d
$$;
REVOKE ALL ON FUNCTION purge_notification_params(int, int) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION notification_request_tenant(uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION purge_notification_params(int, int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
