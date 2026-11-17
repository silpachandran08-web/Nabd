SET lock_timeout = '5s';
-- COM-028: per-number SMS state, the same for every clinic, so no tenant and no RLS (like shedlock): the number is only the keyed
-- FieldCrypto hash, never the digits. marketing_opt_out_at = an SMS STOP reached us (the consent itself is withdrawn in Core through
-- its consent API); dnd/dnd_checked_at = the BSP's DND (NCPR) scrub answer and when it was given. A promotional SMS is refused when
-- the number opted out, is DND, or its DND answer is missing or older than comms.sms.dnd.max-age and cannot be refreshed (fail closed).
CREATE TABLE sms_numbers (
  phone_hash           bytea PRIMARY KEY,
  marketing_opt_out_at timestamptz,
  dnd                  boolean,
  dnd_checked_at       timestamptz
);

-- The STOP handler reads the clinic of the most recent SMS to the number (the STOP's context, like a WhatsApp reply) through the
-- dlr_lookup policy (V26).
CREATE INDEX idx_outbound_messages_sms_to ON outbound_messages (to_hash, created_at DESC) WHERE channel = 'sms';

ALTER TABLE send_refusals DROP CONSTRAINT send_refusals_reason_check;
ALTER TABLE send_refusals ADD CONSTRAINT send_refusals_reason_check
  CHECK (reason IN ('no-marketing-consent','no-messaging-consent','minor-subject','OPTED_OUT','DND','DND_CACHE_STALE'));
