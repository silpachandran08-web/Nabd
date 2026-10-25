-- COM-011: sender model and inbound routing on the shared Nabd number.
CREATE TABLE sender_accounts (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid,                 -- NULL = shared Nabd sender
  kind                 text NOT NULL CHECK (kind IN ('SHARED','TENANT')),
  provider             text NOT NULL CHECK (provider IN ('meta_cloud','bsp')),
  waba_id              text,
  phone_number_id      text NOT NULL UNIQUE,
  display_phone        text,
  display_name         text,
  app_id               text,
  access_token_ref     text NOT NULL,        -- secret-store key, never the token
  quality_rating       text,
  messaging_limit_tier text,
  status               text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verifying','active','restricted','disabled')),
  region               text,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  CHECK ((kind = 'SHARED') = (tenant_id IS NULL))
);
ALTER TABLE sender_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sender_accounts FORCE ROW LEVEL SECURITY;
-- Shared rows are readable by every tenant's sends (they hold a secret-store key name, not a secret); only the owner writes TENANT rows.
CREATE POLICY sender_read ON sender_accounts FOR SELECT
  USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY sender_write ON sender_accounts FOR ALL
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE phone_routes (
  phone_hash        bytea NOT NULL,
  sender_account_id uuid NOT NULL,
  tenant_id         uuid NOT NULL,
  last_outbound_at  timestamptz NOT NULL,
  window_expires_at timestamptz NOT NULL,
  PRIMARY KEY (phone_hash, sender_account_id, tenant_id)
);
CREATE INDEX idx_phone_routes_recent ON phone_routes (phone_hash, sender_account_id, last_outbound_at DESC);
ALTER TABLE phone_routes ENABLE ROW LEVEL SECURITY;
ALTER TABLE phone_routes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_write ON phone_routes FOR ALL
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
-- An inbound message names no tenant: the router reads the candidates of one phone on one sender with app.route_lookup = 'on'
-- (set_config(..., true), SELECT only, InboundRouter is the one caller). shortcut: a GUC instead of a SECURITY DEFINER owner role.
CREATE POLICY route_lookup ON phone_routes FOR SELECT
  USING (current_setting('app.route_lookup', true) = 'on');
