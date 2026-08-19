-- NB-261: the seven-state audited tenant lifecycle. Renames the four states V1 shipped with to the
-- full ladder NB-267's dunning task already names ("the active/trialing/overdue/suspended ladder")
-- plus provisioning (before go_live — see ProvisioningStepRunner) and the offboarding/offboarded
-- pair (DPO-04's compliance grace window before a tenant's data is actually removed).
UPDATE tenants SET status = 'trialing' WHERE status = 'trial';

ALTER TABLE tenants DROP CONSTRAINT tenants_status_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_status_check CHECK (status IN (
  'provisioning', 'trialing', 'active', 'overdue', 'suspended', 'offboarding', 'offboarded'
));
ALTER TABLE tenants ALTER COLUMN status SET DEFAULT 'provisioning';

-- Per-domain audit table, same precedent as master.login_attempts rather than one shared polymorphic
-- ledger — NB-238 (a general cross-domain immutable audit trail service) is a separate, not-yet-built
-- task; this table only ever records tenant lifecycle transitions.
-- ON DELETE CASCADE: a tenant NB-259 rolls back (a botched provisioning attempt that never went
-- live) should leave no trace, same as the owner/brand/role rows that rollback already deletes —
-- this is not the normal offboarding path, where the tenant row itself is never deleted.
CREATE TABLE tenant_lifecycle_events (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  from_status  text NOT NULL,
  to_status    text NOT NULL,
  changed_by   uuid NOT NULL REFERENCES master.operators(id),
  reason       text NOT NULL,
  changed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenant_lifecycle_events_tenant ON tenant_lifecycle_events (tenant_id, changed_at DESC);
