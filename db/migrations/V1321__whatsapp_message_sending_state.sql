SET lock_timeout = '5s';
-- OPS-012: WhatsAppSendHandler now commits intent before calling Meta with no transaction open. 'sending' = the
-- provider call is in progress (or its outcome is unknown after a crash); a second worker that sees a fresh
-- 'sending' row backs off instead of sending the same message again. Statuses are internal (not in the API).
ALTER TABLE whatsapp_messages DROP CONSTRAINT whatsapp_messages_status_check;
ALTER TABLE whatsapp_messages ADD CONSTRAINT whatsapp_messages_status_check
  CHECK (status IN ('queued', 'sending', 'sent', 'delivered', 'read', 'failed', 'blocked'));
