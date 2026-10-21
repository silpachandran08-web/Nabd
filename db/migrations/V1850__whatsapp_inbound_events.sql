-- COM-001: persist-then-ack for Meta's WhatsApp webhook. The controller verifies the HMAC, stores one encrypted row per
-- change item (message, status, template event) keyed by a dedupe key and answers 200; WhatsAppInboundProcessor works the
-- rows off asynchronously. A row has no tenant until the processor routes it, so the app role never reads or writes these
-- rows directly: every access is a narrow SECURITY DEFINER function (precedent: claim_outbox_events, V44).
-- V46/V47 (whatsapp_messages, whatsapp_templates) already have ENABLE + FORCE RLS and per-command policies from V211 (OPS-003,
-- every table with tenant_id) and their apply_* functions were adopted by nabd_definer in V210; a test pins both.
SET lock_timeout = '5s';

CREATE TABLE whatsapp_inbound_events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       uuid REFERENCES tenants(id),          -- NULL until the processor routes the event
  source          text NOT NULL DEFAULT 'meta_whatsapp' CHECK (source IN ('meta_whatsapp')),
  dedupe_key      text NOT NULL UNIQUE,                 -- msg:<wamid> | st:<wamid>:<status> | tpl:<id>:<event>:<ts> | ...
  field           text NOT NULL,                        -- Meta change field: messages, message_template_status_update, ...
  phone_number_id text,
  body_enc        bytea,                                -- FieldCipher v1 (AES-GCM, kid prefix); NULL after the 30-day purge
  status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'done', 'ignored', 'dead')),
  attempts        int NOT NULL DEFAULT 0,
  locked_until    timestamptz,
  received_at     timestamptz NOT NULL DEFAULT now(),
  processed_at    timestamptz,
  last_error_code text
);
CREATE INDEX idx_whatsapp_inbound_claimable ON whatsapp_inbound_events (status, received_at)
  WHERE status IN ('pending', 'processing');
CREATE INDEX idx_whatsapp_inbound_dead ON whatsapp_inbound_events (id) WHERE status = 'dead';
CREATE INDEX idx_whatsapp_inbound_received ON whatsapp_inbound_events (received_at);

-- One round trip, one transaction for the whole webhook batch; a repeated dedupe key is dropped. Returns rows stored.
CREATE FUNCTION store_whatsapp_inbound_events(p_keys text[], p_fields text[], p_numbers text[], p_bodies_hex text[])
RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH i AS (
    INSERT INTO whatsapp_inbound_events (dedupe_key, field, phone_number_id, body_enc)
    SELECT k, f, n, decode(b, 'hex') FROM unnest(p_keys, p_fields, p_numbers, p_bodies_hex) AS u(k, f, n, b)
    ON CONFLICT (dedupe_key) DO NOTHING
    RETURNING 1)
  SELECT count(*)::int FROM i
$$;
REVOKE ALL ON FUNCTION store_whatsapp_inbound_events(text[], text[], text[], text[]) FROM PUBLIC;

-- Same claim/lease rules as claim_outbox_events: pending rows, or processing rows whose lease expired (a crashed worker).
CREATE FUNCTION claim_whatsapp_inbound_events(p_limit int, p_lease_seconds int)
RETURNS SETOF whatsapp_inbound_events
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  UPDATE whatsapp_inbound_events
  SET status = 'processing',
      attempts = attempts + 1,
      locked_until = now() + make_interval(secs => p_lease_seconds)
  WHERE id IN (
    SELECT id FROM whatsapp_inbound_events
    WHERE status = 'pending' OR (status = 'processing' AND locked_until < now())
    ORDER BY received_at, id
    LIMIT p_limit
    FOR UPDATE SKIP LOCKED)
  RETURNING *
$$;
REVOKE ALL ON FUNCTION claim_whatsapp_inbound_events(int, int) FROM PUBLIC;

-- Fenced finish: only the holder of the claim (same attempts value, still 'processing') can finish a row.
-- p_status 'pending' hands the row back for a retry; the other values are terminal.
CREATE FUNCTION finish_whatsapp_inbound_event(p_id bigint, p_attempts int, p_status text, p_tenant uuid, p_error text)
RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  WITH u AS (
    UPDATE whatsapp_inbound_events
    SET status = p_status,
        tenant_id = COALESCE(p_tenant, tenant_id),
        locked_until = NULL,
        processed_at = CASE WHEN p_status = 'pending' THEN NULL ELSE now() END,
        last_error_code = left(p_error, 64)
    WHERE id = p_id AND attempts = p_attempts AND status = 'processing'
    RETURNING 1)
  SELECT EXISTS (SELECT 1 FROM u)
$$;
REVOKE ALL ON FUNCTION finish_whatsapp_inbound_event(bigint, int, text, uuid, text) FROM PUBLIC;

-- Retention: dedupe keys 90 days (longer than Meta's 7-day retry window), raw body 30 days. At most p_batch rows per call
-- (keys first). A row still unfinished after the body is gone cannot be processed any more and is dead-lettered.
CREATE FUNCTION purge_whatsapp_inbound_events(p_body_days int, p_key_days int, p_batch int)
RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = public SET lock_timeout = '1s'
AS $$
  WITH d AS (
    DELETE FROM whatsapp_inbound_events WHERE id IN (
      SELECT id FROM whatsapp_inbound_events WHERE received_at < now() - make_interval(days => p_key_days)
      LIMIT p_batch FOR UPDATE SKIP LOCKED)
    RETURNING 1),
  b AS (
    UPDATE whatsapp_inbound_events
    SET body_enc = NULL,
        status = CASE WHEN status IN ('pending', 'processing') THEN 'dead' ELSE status END,
        last_error_code = CASE WHEN status IN ('pending', 'processing') THEN 'expired' ELSE last_error_code END
    WHERE id IN (
      SELECT id FROM whatsapp_inbound_events
      WHERE body_enc IS NOT NULL AND received_at < now() - make_interval(days => p_body_days)
      LIMIT greatest(p_batch - (SELECT count(*) FROM d), 0) FOR UPDATE SKIP LOCKED)
    RETURNING 1)
  SELECT ((SELECT count(*) FROM d) + (SELECT count(*) FROM b))::int
$$;
REVOKE ALL ON FUNCTION purge_whatsapp_inbound_events(int, int, int) FROM PUBLIC;

-- Gauges: backlog, dead letters, age of the oldest unfinished row.
CREATE FUNCTION whatsapp_inbound_stats()
RETURNS TABLE (pending bigint, dead bigint, oldest_seconds bigint)
LANGUAGE sql SECURITY DEFINER STABLE SET search_path = public
AS $$
  SELECT (SELECT count(*) FROM whatsapp_inbound_events WHERE status IN ('pending', 'processing')),
         (SELECT count(*) FROM whatsapp_inbound_events WHERE status = 'dead'),
         COALESCE((SELECT extract(epoch FROM now() - min(received_at))::bigint
                   FROM whatsapp_inbound_events WHERE status IN ('pending', 'processing')), 0)
$$;
REVOKE ALL ON FUNCTION whatsapp_inbound_stats() FROM PUBLIC;

-- Status callbacks carry only the wamid: same forward-only rule as apply_whatsapp_status (V46), but it also returns the
-- tenant and message of the row it moved, so the processor can publish comms.message.status_changed.v1 for exactly that move.
CREATE FUNCTION apply_whatsapp_status_tenant(p_wamid text, p_status text, p_error text)
RETURNS TABLE (tenant_id uuid, message_id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
  UPDATE whatsapp_messages m
  SET status = p_status, error = COALESCE(p_error, m.error)
  WHERE m.wamid = p_wamid
    AND CASE p_status
          WHEN 'delivered' THEN m.status IN ('sent', 'failed')
          WHEN 'read'      THEN m.status IN ('sent', 'delivered', 'failed')
          WHEN 'failed'    THEN m.status = 'sent'
          ELSE false
        END
  RETURNING m.tenant_id, m.id
$$;
REVOKE ALL ON FUNCTION apply_whatsapp_status_tenant(text, text, text) FROM PUBLIC;

-- Which clinic sent the message a patient is replying to (context.id of the inbound message)?
CREATE FUNCTION whatsapp_message_tenant(p_wamid text)
RETURNS uuid
LANGUAGE sql SECURITY DEFINER STABLE SET search_path = public
AS $$
  SELECT tenant_id FROM whatsapp_messages WHERE wamid = p_wamid
$$;
REVOKE ALL ON FUNCTION whatsapp_message_tenant(text) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION store_whatsapp_inbound_events(text[], text[], text[], text[]) TO nabd_app;
    GRANT EXECUTE ON FUNCTION claim_whatsapp_inbound_events(int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION finish_whatsapp_inbound_event(bigint, int, text, uuid, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION purge_whatsapp_inbound_events(int, int, int) TO nabd_app;
    GRANT EXECUTE ON FUNCTION whatsapp_inbound_stats() TO nabd_app;
    GRANT EXECUTE ON FUNCTION apply_whatsapp_status_tenant(text, text, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION whatsapp_message_tenant(text) TO nabd_app;
  END IF;
END $$;

SELECT enforce_tenant_rls('whatsapp_inbound_events');
SELECT nabd_adopt_definer_functions();
