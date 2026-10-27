SET lock_timeout = '5s';
-- FIN-020: UPI payment links / QR as payment intents, gateway webhook inbox, gateway columns on payments.
CREATE TABLE payment_intents (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  invoice_id        uuid REFERENCES invoices(id),
  purpose           text NOT NULL DEFAULT 'invoice' CHECK (purpose IN ('invoice','deposit','instalment','package','membership')),
  purpose_ref       uuid,
  kind              text NOT NULL CHECK (kind IN ('link','qr','mandate_debit')),
  provider          text NOT NULL,
  provider_order_id text NOT NULL,
  amount_minor      bigint NOT NULL CHECK (amount_minor > 0),
  currency          char(3) NOT NULL,
  payer_kind        text NOT NULL DEFAULT 'patient' CHECK (payer_kind IN ('patient','caregiver','other')),
  payer_ref         uuid,
  status            text NOT NULL DEFAULT 'created' CHECK (status IN ('created','paid','failed','expired','cancelled','refunded','partially_refunded')),
  url               text,
  qr_payload        text,
  expires_at        timestamptz NOT NULL,
  paid_at           timestamptz,
  payment_id        uuid REFERENCES invoice_payments(id),
  gateway_ref       text,
  created_by        uuid REFERENCES staff(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, provider, provider_order_id)
);
CREATE INDEX idx_payment_intents_status ON payment_intents (tenant_id, status, expires_at);
SELECT enforce_tenant_rls('payment_intents');

-- Persist-then-ack inbox. Only the parsed fields are kept (never the raw payload: it can carry customer contact data).
CREATE TABLE gateway_webhook_events (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  provider          text NOT NULL,
  event_id          text NOT NULL,
  event_type        text NOT NULL,
  provider_order_id text,
  gateway_ref       text,
  amount_minor      bigint,
  payload_sha256    text NOT NULL,
  received_at       timestamptz NOT NULL DEFAULT now(),
  processed_at      timestamptz,
  error             text,
  UNIQUE (provider, event_id)
);
SELECT enforce_tenant_rls('gateway_webhook_events');

ALTER TABLE invoice_payments
  ADD COLUMN gateway text,
  ADD COLUMN gateway_ref text,
  ADD COLUMN payment_intent_id uuid REFERENCES payment_intents(id),
  ADD COLUMN payer_kind text,
  ADD COLUMN payer_ref uuid;
