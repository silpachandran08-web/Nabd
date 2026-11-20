SET lock_timeout = '5s';
-- COM-015 (T-110, T-365): message counts and cost per clinic. The spec names this comms/V11; the S25 Comms block is V31-V33.

-- What one message cost, kept exactly: cost_micros = millionths of the currency's major unit (Meta's India utility rate is 11.5 paise,
-- which the existing cost_minor cannot hold). priced_at = when the pricing was captured (Meta status webhook, SMS delivery report);
-- a message is priced once.
ALTER TABLE outbound_messages ADD COLUMN cost_micros bigint, ADD COLUMN priced_at timestamptz;
-- migration-lint: allow MIG-001 comms database, partial index on the column added in this file (no row has it set)
CREATE INDEX idx_outbound_messages_priced ON outbound_messages (priced_at, tenant_id) WHERE priced_at IS NOT NULL;
-- The rollup must find the clinics that have priced messages (like fallback_scan, V27): SELECT only, switched on per transaction
-- by UsageService.rollup; the aggregation itself runs under each clinic's own tenant.
CREATE POLICY usage_scan ON outbound_messages FOR SELECT
  USING (priced_at IS NOT NULL AND current_setting('app.usage_scan', true) = 'on');

-- The provider price list is data, not code: one row per deployment region, channel and pricing category from valid_from on.
-- The same for every clinic, so no tenant and no RLS (like sms_numbers). Ops replace the rows from the provider's rate card.
CREATE TABLE message_prices (
  region                 text NOT NULL,
  channel                text NOT NULL CHECK (channel IN ('whatsapp','sms','email','voice')),
  pricing_category       text NOT NULL,
  valid_from             date NOT NULL,
  currency               char(3) NOT NULL,
  price_micros           bigint NOT NULL CHECK (price_micros >= 0),
  needs_commercial_check boolean NOT NULL DEFAULT true,
  note                   text,
  PRIMARY KEY (region, channel, pricing_category, valid_from)
);
-- NEEDS COMMERCIAL CHECK: every rate below was typed from Meta's public per-message rate card (1 July 2025) and has not been
-- compared with a Nabd invoice. Meta bills a WhatsApp Business Account in its own billing currency (USD outside India).
-- 'service' (free-form replies inside the 24 h window) is free. SMS: the BSP is not contracted yet, so the rate is 0 until ops
-- set it; a delivery report that carries its own cost wins over this list.
INSERT INTO message_prices (region, channel, pricing_category, valid_from, currency, price_micros, note) VALUES
  ('in', 'whatsapp', 'marketing',        '2025-07-01', 'INR', 784600, 'NEEDS COMMERCIAL CHECK: Meta rate card India'),
  ('in', 'whatsapp', 'utility',          '2025-07-01', 'INR', 115000, 'NEEDS COMMERCIAL CHECK: Meta rate card India'),
  ('in', 'whatsapp', 'authentication',   '2025-07-01', 'INR', 115000, 'NEEDS COMMERCIAL CHECK: Meta rate card India'),
  ('in', 'whatsapp', 'service',          '2025-07-01', 'INR', 0,      'NEEDS COMMERCIAL CHECK: free per Meta'),
  ('sa', 'whatsapp', 'marketing',        '2025-07-01', 'USD', 45500,  'NEEDS COMMERCIAL CHECK: Meta rate card Saudi Arabia'),
  ('sa', 'whatsapp', 'utility',          '2025-07-01', 'USD', 10700,  'NEEDS COMMERCIAL CHECK: Meta rate card Saudi Arabia'),
  ('sa', 'whatsapp', 'authentication',   '2025-07-01', 'USD', 10700,  'NEEDS COMMERCIAL CHECK: Meta rate card Saudi Arabia'),
  ('sa', 'whatsapp', 'service',          '2025-07-01', 'USD', 0,      'NEEDS COMMERCIAL CHECK: free per Meta'),
  ('in', 'sms',      'otp',              '2025-07-01', 'INR', 0,      'NEEDS COMMERCIAL CHECK: BSP contract rate not known'),
  ('in', 'sms',      'service_implicit', '2025-07-01', 'INR', 0,      'NEEDS COMMERCIAL CHECK: BSP contract rate not known'),
  ('in', 'sms',      'service_explicit', '2025-07-01', 'INR', 0,      'NEEDS COMMERCIAL CHECK: BSP contract rate not known'),
  ('in', 'sms',      'promotional',      '2025-07-01', 'INR', 0,      'NEEDS COMMERCIAL CHECK: BSP contract rate not known');

-- One row per clinic, clinic-local day, channel and pricing category; recomputed from outbound_messages by the rollup (idempotent).
-- cost_micros is the exact sum (the month total is rounded once); cost_minor is the day's rounded value.
-- reported_at = comms.usage.reported.v1 was published for this closed day.
CREATE TABLE message_usage_daily (
  tenant_id        uuid NOT NULL,
  day              date NOT NULL,
  channel          text NOT NULL,
  pricing_category text NOT NULL,
  count            int NOT NULL CHECK (count >= 0),
  cost_micros      bigint NOT NULL CHECK (cost_micros >= 0),
  cost_minor       bigint NOT NULL CHECK (cost_minor >= 0),
  currency         char(3) NOT NULL,
  reported_at      timestamptz,
  PRIMARY KEY (tenant_id, day, channel, pricing_category)
);
ALTER TABLE message_usage_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_usage_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON message_usage_daily
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
