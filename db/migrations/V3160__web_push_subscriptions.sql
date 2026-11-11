SET lock_timeout = '5s';
-- FEF-048: one Web Push subscription per staff member and browser/device (endpoint). The auth secret is encrypted at rest
-- (FieldCipher, AAD tenant:web_push_subscriptions:auth_secret:id); p256dh is the browser's public key. No PHI: the push
-- payload carries a token number only. The unique key's (tenant_id, staff_id) prefix serves the per-staff lookup.
CREATE TABLE web_push_subscriptions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  staff_id        uuid NOT NULL REFERENCES staff (id),
  endpoint        text NOT NULL,
  p256dh          text NOT NULL,
  auth_secret_enc bytea NOT NULL,
  device_label    text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  last_success_at timestamptz,
  failure_count   int NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, staff_id, endpoint)
);
SELECT enforce_tenant_rls('web_push_subscriptions');
