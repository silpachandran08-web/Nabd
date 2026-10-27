SET lock_timeout = '5s';
-- FIN-019: the clinic's own payment gateway account. Credentials live ONLY in the tenant integration secret store (OPS-058);
-- this table keeps pointers (secret_ref, webhook_secret_ref are secret NAMES, never values). One live account per clinic.
CREATE TABLE payment_gateway_accounts (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  provider           text NOT NULL CHECK (provider IN ('razorpay','tap','hyperpay','network_intl')),
  merchant_ref       text,
  secret_ref         text NOT NULL,
  webhook_secret_ref text,
  status             text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','disabled')),
  last_test_status   text CHECK (last_test_status IN ('succeeded','failed')),
  last_test_at       timestamptz,
  connected_by       uuid REFERENCES staff(id),
  connected_at       timestamptz NOT NULL DEFAULT now(),
  disabled_at        timestamptz
);
CREATE UNIQUE INDEX uq_payment_gateway_one_live ON payment_gateway_accounts (tenant_id) WHERE status <> 'disabled';
SELECT enforce_tenant_rls('payment_gateway_accounts');
