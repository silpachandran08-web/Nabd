SET lock_timeout = '5s';
-- COM-021: sender throttle state and quality history. (Spec names this V14; the Core-side numbering is E15's V6.)
ALTER TABLE sender_accounts
  ADD COLUMN throttle_mode       text NOT NULL DEFAULT 'auto' CHECK (throttle_mode IN ('auto','bulk_paused','replies_only')),
  ADD COLUMN throttle_reason     text,
  ADD COLUMN throttle_expires_at timestamptz,        -- set only while an ops override is active
  ADD COLUMN block_rate_24h      numeric,
  ADD COLUMN green_since         timestamptz;        -- start of the current unbroken GREEN run (48 h to auto-resume)

CREATE TABLE sender_quality_events (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- also the CloudEvents id of the published event
  sender_account_id uuid NOT NULL REFERENCES sender_accounts (id),
  tenant_id         uuid,                                              -- NULL = shared Nabd sender
  kind              text NOT NULL CHECK (kind IN ('rating','tier','block_rate','override')),
  from_value        text,
  to_value          text,
  reason            text,
  throttle_mode     text NOT NULL DEFAULT 'auto',
  at                timestamptz NOT NULL DEFAULT now(),
  published_at      timestamptz
);
CREATE INDEX idx_sender_quality_events_sender ON sender_quality_events (sender_account_id, at DESC);
CREATE INDEX idx_sender_quality_events_unpublished ON sender_quality_events (id) WHERE published_at IS NULL;
ALTER TABLE sender_quality_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE sender_quality_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_read ON sender_quality_events FOR SELECT
  USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);
-- shortcut: same GUC pattern as phone_routes.route_lookup (V5): the sender-quality service is the one caller that sets app.sender_admin
-- (it works across tenants and on the SHARED rows, which no tenant may write). Upgrade to a SECURITY DEFINER owner role when one exists.
CREATE POLICY sender_admin ON sender_quality_events FOR ALL
  USING (current_setting('app.sender_admin', true) = 'on') WITH CHECK (current_setting('app.sender_admin', true) = 'on');
CREATE POLICY sender_admin ON sender_accounts FOR ALL
  USING (current_setting('app.sender_admin', true) = 'on') WITH CHECK (current_setting('app.sender_admin', true) = 'on');
-- block-rate job: aggregate read of one sender's messages across tenants (SELECT only)
CREATE POLICY sender_admin_read ON outbound_messages FOR SELECT USING (current_setting('app.sender_admin', true) = 'on');
