-- PLT-012: in-app alert shown to each Owner when emergency access is used. No patient data, ever.
CREATE TABLE break_glass_alerts (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  grant_id        uuid NOT NULL REFERENCES break_glass_grants(id),
  owner_staff_id  uuid NOT NULL REFERENCES staff(id),
  message         text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (grant_id, owner_staff_id)
);
CREATE INDEX idx_bg_alerts_owner ON break_glass_alerts (owner_staff_id, created_at DESC);
ALTER TABLE break_glass_alerts ENABLE ROW LEVEL SECURITY;
CREATE POLICY bga_select ON break_glass_alerts FOR SELECT USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bga_insert ON break_glass_alerts FOR INSERT WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bga_update ON break_glass_alerts FOR UPDATE USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bga_delete ON break_glass_alerts FOR DELETE USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
ALTER TABLE break_glass_alerts FORCE ROW LEVEL SECURITY;
