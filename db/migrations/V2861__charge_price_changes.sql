SET lock_timeout = '5s';
-- FIN-016: price change log, written by the setup service in the same transaction as the update (not a trigger: the actor is known).
-- Values are text so one column holds amounts, flags and tax categories; issued invoices keep their own line prices.
CREATE TABLE charge_price_changes (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  charge_id      uuid NOT NULL REFERENCES charge_catalogue(id) ON DELETE CASCADE,
  doctor_id      uuid REFERENCES staff(id),
  field          text NOT NULL CHECK (field IN ('base_amount','follow_up_amount','emergency_amount','doctor_fee','tax_category','active')),
  old_value      text,
  new_value      text,
  actor_id       uuid NOT NULL REFERENCES staff(id),
  effective_from timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_charge_price_changes_charge ON charge_price_changes (tenant_id, charge_id, created_at DESC);
SELECT enforce_tenant_rls('charge_price_changes');
