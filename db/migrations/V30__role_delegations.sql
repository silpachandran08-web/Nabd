-- E06 RBAC & Permissions (NB-057): delegation — borrow another role's grants for a bounded window
-- (e.g. covering a doctor's leave) without permanently editing anyone's role assignment.
-- Enforcement merges the delegated role's grants into the receiving staff member's token at mint
-- time (see AuthService.mintTokenPair) rather than inventing a second permission concept.
CREATE TABLE role_delegations (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  staff_id           uuid NOT NULL REFERENCES staff(id),
  delegated_role_id  uuid NOT NULL REFERENCES roles(id),
  granted_by         uuid NOT NULL REFERENCES staff(id),
  reason             text NOT NULL,
  starts_at          timestamptz NOT NULL DEFAULT now(),
  expires_at         timestamptz NOT NULL,
  revoked_at         timestamptz,
  revoked_reason     text CHECK (revoked_reason IN ('expired','manual'))
);
ALTER TABLE role_delegations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_delegations
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_role_delegations_staff_active ON role_delegations (staff_id) WHERE revoked_at IS NULL;
