-- Support access grants (NB-266, SSA-04): a platform operator's time-boxed,
-- reason-logged permission to view one tenant's data through the redacted
-- "support view" (see SupportAccessService). Lives in master schema, no
-- RLS — same precedent as master.support_tickets (V13): this is inherently
-- cross-tenant platform-operator activity, gated by support_access:view
-- (NB-257's matrix), not by tenant-scoped row security.
CREATE TABLE master.support_access_grants (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  operator_id  uuid NOT NULL REFERENCES master.operators(id),
  reason       text NOT NULL,
  granted_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  revoked_at   timestamptz
);

CREATE INDEX idx_support_access_tenant ON master.support_access_grants (tenant_id);
CREATE INDEX idx_support_access_operator ON master.support_access_grants (operator_id);
