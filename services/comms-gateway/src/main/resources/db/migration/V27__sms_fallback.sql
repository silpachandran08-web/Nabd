SET lock_timeout = '5s';
-- COM-027: SMS fallback when WhatsApp does not deliver (T-309). The spec names this comms/V18; the S24 Comms block is V27-V29.

-- A number Meta reports as not on WhatsApp (error 131026) is not on WhatsApp for any clinic: one row per phone hash (the same
-- FieldCrypto HMAC as outbound_messages.to_hash), tenant_id always NULL. Later sends to it go SMS-first until the mark is 30 days old.
CREATE TABLE channel_reachability (
  phone_hash         bytea PRIMARY KEY,
  tenant_id          uuid,
  whatsapp_reachable boolean NOT NULL,
  last_checked_at    timestamptz NOT NULL DEFAULT now(),
  reason_code        text
);
ALTER TABLE channel_reachability ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_reachability FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON channel_reachability
  USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);

-- fallback_decided_at: the fallback engine has dealt with this WhatsApp message (sent the SMS, or found nothing to send); it is
-- never looked at again. One SMS per original: the unique index refuses a second row with the same fallback_of before the BSP call.
ALTER TABLE outbound_messages ADD COLUMN fallback_decided_at timestamptz;
-- migration-lint: allow MIG-001 comms database, partial unique index on a column no code path has written before this story
CREATE UNIQUE INDEX idx_outbound_messages_fallback_of ON outbound_messages (fallback_of) WHERE fallback_of IS NOT NULL;
-- migration-lint: allow MIG-001 comms database, partial index on the column added in this file (no row has it set)
CREATE INDEX idx_outbound_messages_fallback_due ON outbound_messages (updated_at)
  WHERE channel = 'whatsapp' AND fallback_decided_at IS NULL AND status IN ('queued','sending','sent','failed');

-- The fallback timer must see due WhatsApp messages of every tenant (like scheduler_scan, V10): SELECT only, switched on per
-- transaction by SmsFallback.tick; every write runs under the row's own tenant.
CREATE POLICY fallback_scan ON outbound_messages FOR SELECT
  USING (channel = 'whatsapp' AND fallback_decided_at IS NULL AND current_setting('app.fallback_scan', true) = 'on');

-- The desk's 'Send as SMS' is audited like the other inbox actions.
ALTER TABLE inbox_audit DROP CONSTRAINT inbox_audit_action_check;
ALTER TABLE inbox_audit ADD CONSTRAINT inbox_audit_action_check
  CHECK (action IN ('reply','template_send','assign','mode','note','tag_clinical','send_as_sms')) NOT VALID;
ALTER TABLE inbox_audit VALIDATE CONSTRAINT inbox_audit_action_check; -- a widened list: every existing row passes
