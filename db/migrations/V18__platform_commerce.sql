-- E22 Platform Console — SaaS Operations
-- Refs: NB-267 (subscriptions/dunning), NB-268 (discount approval queue),
-- NB-269 (pricing & packaging), NB-270 (territories, reads these), NB-273
-- (seat usage, reads plans.seat_limit + subscriptions — no new table).
-- master schema: no RLS, same precedent as support_tickets/support_access_grants.

CREATE TABLE master.plans (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code                 text NOT NULL UNIQUE,
  name                 text NOT NULL,
  monthly_price_cents  integer NOT NULL CHECK (monthly_price_cents >= 0),
  currency             text NOT NULL CHECK (currency IN ('INR', 'SAR')),
  seat_limit           integer NOT NULL CHECK (seat_limit > 0),
  active               boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON master.plans
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One row per tenant — a subscription is current state, not a history (renewal_date moves forward
-- in place; dunning status itself lives on tenants.status via TenantLifecycleService, not duplicated
-- here, so there is exactly one place that says whether a tenant is active/overdue/suspended).
CREATE TABLE master.subscriptions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL UNIQUE REFERENCES tenants(id),
  plan_id       uuid NOT NULL REFERENCES master.plans(id),
  mrr_cents     integer NOT NULL CHECK (mrr_cents >= 0),
  currency      text NOT NULL CHECK (currency IN ('INR', 'SAR')),
  renewal_date  date NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_renewal ON master.subscriptions(renewal_date);
CREATE TRIGGER trg_subscriptions_updated_at BEFORE UPDATE ON master.subscriptions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.discount_requests (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  requested_by   uuid NOT NULL REFERENCES master.operators(id),
  percent        numeric(5,2) NOT NULL CHECK (percent > 0 AND percent <= 100),
  reason         text NOT NULL,
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('auto_approved', 'pending', 'approved', 'rejected')),
  reviewed_by    uuid REFERENCES master.operators(id),
  reviewed_at    timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_discount_requests_tenant ON master.discount_requests(tenant_id, created_at DESC);

-- staff carries per-tenant RLS (V1) keyed off current_setting('app.tenant_id') — exactly right for
-- every tenant-scoped caller, wrong for platform aggregation that legitimately spans every tenant at
-- once (seat usage in billing, headcount in territories). Same narrow SECURITY DEFINER escape hatch
-- as search_audit_log() (V16): one read-only, REVOKE-ALL-locked function, nothing else exposed.
CREATE FUNCTION staff_counts_by_tenant()
RETURNS TABLE (tenant_id uuid, staff_count bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT staff.tenant_id, count(*) FROM staff GROUP BY staff.tenant_id;
$$;
REVOKE ALL ON FUNCTION staff_counts_by_tenant() FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION staff_counts_by_tenant() TO <app_role>;
