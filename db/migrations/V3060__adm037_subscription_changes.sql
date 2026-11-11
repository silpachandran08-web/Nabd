SET lock_timeout = '5s';
-- ADM-037: the owner changes plan without a ticket. master schema = operator/control plane (no RLS, listed in
-- rls-exemptions.txt like master.subscriptions); reached only through the owner's /v1/setup/subscription endpoints, which
-- always filter by the caller's tenant. No PHI. Payment tokens never reach Core: the billing gateway owns them.

-- One row per requested change. 'scheduled' = a downgrade waiting for the renewal date; 'applied' = the subscription moved;
-- 'cancelled' = replaced by a newer request before it applied. step_up_id is the step-up token's jti, unique, so one MFA
-- confirmation changes the plan once (replay or reuse for another target is refused).
CREATE TABLE master.subscription_changes (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  from_plan_id       uuid REFERENCES master.plans(id),
  to_plan_id         uuid NOT NULL REFERENCES master.plans(id),
  to_plan_version    int  NOT NULL,
  direction          text NOT NULL CHECK (direction IN ('upgrade','downgrade')),
  effective          text NOT NULL CHECK (effective IN ('now','renewal')),
  effective_at       timestamptz NOT NULL,
  proration_minor    bigint NOT NULL,
  currency           text NOT NULL CHECK (currency IN ('INR','SAR','AED','QAR')),
  status             text NOT NULL CHECK (status IN ('scheduled','applied','cancelled')),
  requested_by_staff uuid NOT NULL,
  step_up_id         uuid NOT NULL UNIQUE,
  created_at         timestamptz NOT NULL DEFAULT now(),
  applied_at         timestamptz
);
CREATE INDEX idx_subscription_changes_tenant ON master.subscription_changes (tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_subscription_changes_scheduled ON master.subscription_changes (tenant_id) WHERE status = 'scheduled';

-- Nabd's invoices to the clinic (the control plane's own billing, not the clinic's patient invoices). Amounts in minor units,
-- net of tax: the tax line (GST of Nabd's Indian entity, VAT in the Gulf) is added by the billing gateway that issues the
-- legal document, so no rate is invented here.
CREATE SEQUENCE master.platform_invoice_seq;
CREATE TABLE master.platform_invoices (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  number        text NOT NULL UNIQUE,
  kind          text NOT NULL CHECK (kind IN ('proration','renewal')),
  period_start  date NOT NULL,
  period_end    date NOT NULL,
  amount_minor  bigint NOT NULL CHECK (amount_minor >= 0),
  currency      text NOT NULL CHECK (currency IN ('INR','SAR','AED','QAR')),
  status        text NOT NULL DEFAULT 'due' CHECK (status IN ('due','paid','void')),
  change_id     uuid REFERENCES master.subscription_changes(id),
  issued_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_invoices_tenant ON master.platform_invoices (tenant_id, issued_at DESC, id DESC);
