-- E17 foundation (NB-193 delivery-state tracking, NB-196 consent gate at send time): one row per
-- outbound WhatsApp message. The send itself goes through the outbox (V44) — WhatsAppMessageService
-- inserts the row and publishes a 'whatsapp.send' event in the caller's transaction, so a message is
-- either queued durably or not at all. Meta's delivery webhooks then move status forward.
--
-- ponytail: outbound only. Inbound messages (the 24h session window, NB-192, and the shared inbox,
-- NB-197) need per-tenant numbers to route a reply to the right clinic — add an inbound table when
-- tenants get their own phone_number_id (NB-188), not before.
CREATE TABLE whatsapp_messages (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  patient_id    uuid REFERENCES patients(id),   -- NULL for non-patient sends; consent is only checked when set
  to_phone      text NOT NULL,
  template_name text NOT NULL,
  language      text NOT NULL,
  params        jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- queued: waiting on the outbox. sent: Meta accepted it (wamid set). delivered/read: webhook.
  -- failed: Meta rejected or reported failure. blocked: consent missing/withdrawn at send time.
  status        text NOT NULL DEFAULT 'queued'
                CHECK (status IN ('queued', 'sent', 'delivered', 'read', 'failed', 'blocked')),
  wamid         text UNIQUE,                     -- Meta's message id, the webhook's only handle
  error         text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_whatsapp_messages_updated_at BEFORE UPDATE ON whatsapp_messages
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_whatsapp_messages_patient ON whatsapp_messages (tenant_id, patient_id, created_at DESC);

ALTER TABLE whatsapp_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_messages
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Meta's status webhook carries only the wamid — no tenant — so applying it needs one narrow
-- cross-tenant write, same SECURITY DEFINER precedent as claim_outbox_events (V44). Webhooks can
-- arrive out of order (read before delivered), so status only ever moves forward; 'failed' can
-- still land on a 'sent' message but never overwrites delivered/read.
CREATE FUNCTION apply_whatsapp_status(p_wamid text, p_status text, p_error text)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE whatsapp_messages
  SET status = p_status, error = COALESCE(p_error, error)
  WHERE wamid = p_wamid
    AND CASE p_status
          WHEN 'delivered' THEN status IN ('sent', 'failed')
          WHEN 'read'      THEN status IN ('sent', 'delivered', 'failed')
          WHEN 'failed'    THEN status = 'sent'
          ELSE false
        END;
$$;
REVOKE ALL ON FUNCTION apply_whatsapp_status(text, text, text) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION apply_whatsapp_status(text,text,text) TO <app_role>;
