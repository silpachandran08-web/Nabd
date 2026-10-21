SET lock_timeout = '5s';
-- COM-003: delivery vocabulary gains scheduled, cancelled and expired (COM-023 fills scheduled/expired; a cancelled or
-- expired row is terminal and never becomes 'sent'). attempts is the fencing token of the send handler: every claim
-- bumps it, and a stale worker's completion (older attempt) changes nothing.
ALTER TABLE whatsapp_messages DROP CONSTRAINT whatsapp_messages_status_check;
ALTER TABLE whatsapp_messages ADD CONSTRAINT whatsapp_messages_status_check
  CHECK (status IN ('scheduled', 'queued', 'sending', 'sent', 'delivered', 'read', 'failed', 'blocked', 'cancelled', 'expired')) NOT VALID;
ALTER TABLE whatsapp_messages VALIDATE CONSTRAINT whatsapp_messages_status_check;
ALTER TABLE whatsapp_messages ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE whatsapp_messages ADD COLUMN last_attempt_at timestamptz;
