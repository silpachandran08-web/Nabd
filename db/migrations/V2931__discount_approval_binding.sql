SET lock_timeout = '5s';
-- migration-lint: allow MIG-001 discount_approvals is new in this sprint (V2930, unreleased) and empty in every environment
-- FIN-018 fix round (S18-BUG-1..4).
-- An approval covers the bill it was asked for: the bill fingerprint (charge codes, quantities and list prices, plus the
-- lines already on the visit's invoice) and its list-price total; the issue gate also checks amount and percent.
-- An owner PIN assertion confirms one decision: its id is stored on the approval it decided, unique, so a replay fails.
ALTER TABLE discount_approvals
  ADD COLUMN bill_fingerprint text,
  ADD COLUMN gross_minor bigint CHECK (gross_minor >= 0),
  ADD COLUMN pin_assertion_id uuid;
CREATE UNIQUE INDEX uq_discount_approvals_pin_assertion ON discount_approvals (pin_assertion_id) WHERE pin_assertion_id IS NOT NULL;

-- Owner PIN tries at a desk count against the desk staff member who made them (lockout), never against the owners' logins.
CREATE TABLE pin_assertion_attempts (
  id           uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  staff_id     uuid NOT NULL REFERENCES staff(id),
  succeeded    boolean NOT NULL,
  attempted_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_pin_assertion_attempts_staff ON pin_assertion_attempts (tenant_id, staff_id, attempted_at);
SELECT enforce_tenant_rls('pin_assertion_attempts');
