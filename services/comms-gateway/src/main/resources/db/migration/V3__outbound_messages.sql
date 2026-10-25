-- COM-008: the Comms Gateway owns messages, templates, inbound events and its own channel-consent snapshot.
-- Successors of Core V46/V47/V1850. No foreign keys to Core (own database); ids are carried over by the backfill.
-- Tenant isolation: ADR-07 policy keyed on app.tenant_id, set per message / per transaction by the gateway.

CREATE TABLE outbound_messages (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  request_id          uuid,                      -- notification request; NULL for rows backfilled from before COM-004
  channel             text NOT NULL DEFAULT 'whatsapp' CHECK (channel IN ('whatsapp','sms','email','voice')),
  sender_account_id   uuid,
  patient_ref         uuid,
  to_address_enc      bytea NOT NULL,
  to_hash             bytea NOT NULL,
  template_id         uuid,
  template_name       text NOT NULL,
  language            text NOT NULL,
  params_enc          bytea,
  priority            text NOT NULL DEFAULT 'normal',
  not_before          timestamptz,
  not_after           timestamptz,
  status              text NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('scheduled','queued','sending','sent','delivered','read','failed','blocked','cancelled','expired')),
  provider_message_id text UNIQUE,               -- wamid or SMS id
  fallback_of         uuid,
  thread_id           uuid,
  attempts            int NOT NULL DEFAULT 0,
  last_attempt_at     timestamptz,
  cost_minor          bigint,
  currency            char(3),
  pricing_category    text,
  error_code          text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, request_id, channel)
);
CREATE INDEX idx_outbound_messages_patient ON outbound_messages (tenant_id, patient_ref, created_at DESC);
ALTER TABLE outbound_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbound_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbound_messages
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE whatsapp_templates (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  name              text NOT NULL,
  meta_name         text NOT NULL UNIQUE,
  language          text NOT NULL,
  category          text NOT NULL CHECK (category IN ('UTILITY','MARKETING','AUTHENTICATION')),
  header_text       text NOT NULL,
  body              text NOT NULL,
  param_count       int NOT NULL CHECK (param_count >= 0),
  status            text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('submitting','queued','pending','approved','flagged','paused','disabled','rejected')),
  rejection_reason  text,
  quality_rating    text NOT NULL DEFAULT 'UNKNOWN' CHECK (quality_rating IN ('GREEN','YELLOW','RED','UNKNOWN')),
  meta_template_id  text UNIQUE,
  catalogue_key     text,
  catalogue_version int,
  buttons           jsonb,
  purpose           text,
  submit_attempts   int NOT NULL DEFAULT 0,
  next_attempt_at   timestamptz,
  submitted_at      timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name, language)
);
CREATE UNIQUE INDEX whatsapp_templates_catalogue_once ON whatsapp_templates (tenant_id, catalogue_key, language) WHERE catalogue_key IS NOT NULL;
ALTER TABLE whatsapp_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE whatsapp_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_templates
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Webhook events arrive before the tenant is known (tenant_id NULL until routed): visible only once routed to the bound tenant.
CREATE TABLE inbound_events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       uuid,
  source          text NOT NULL DEFAULT 'meta_whatsapp',
  dedupe_key      text NOT NULL UNIQUE,
  field           text NOT NULL,
  phone_number_id text,
  body_enc        bytea,
  status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processing','done','ignored','dead')),
  attempts        int NOT NULL DEFAULT 0,
  locked_until    timestamptz,
  received_at     timestamptz NOT NULL DEFAULT now(),
  processed_at    timestamptz,
  last_error_code text
);
CREATE INDEX idx_inbound_events_claimable ON inbound_events (status, received_at) WHERE status IN ('pending','processing');
ALTER TABLE inbound_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbound_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbound_events
  USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Fed by patient consent events plus nightly reconciliation; the sender never reads Core's consents.
CREATE TABLE channel_consent_snapshot (
  tenant_id   uuid NOT NULL,
  patient_ref uuid NOT NULL,
  purpose     text NOT NULL CHECK (purpose IN ('messaging','marketing')),
  granted     boolean NOT NULL,
  version     bigint NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, patient_ref, purpose)
);
ALTER TABLE channel_consent_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_consent_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON channel_consent_snapshot
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
