SET lock_timeout = '5s';
-- COM-026: TRAI TCCCPR. Every SMS goes out under a DLT-registered entity, header (sender id) and template; the body is the exact
-- registered text with {#var#} placeholders. tenant_id NULL = Nabd-shared registration (OTP / transactional, CEO-19); a clinic row
-- is its own registration (promotional). Rows are loaded by ops from the DLT portal export: no secrets, no PHI.
CREATE TABLE sms_templates (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid,
  key             text NOT NULL,
  language        text NOT NULL,
  dlt_entity_id   text NOT NULL,
  dlt_template_id text NOT NULL UNIQUE,
  header          text NOT NULL,
  category        text NOT NULL CHECK (category IN ('otp','service_implicit','service_explicit','promotional')),
  body            text NOT NULL,
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (category <> 'promotional' OR tenant_id IS NOT NULL) -- promotional only under the clinic's own registration
);
ALTER TABLE sms_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE sms_templates FORCE ROW LEVEL SECURITY;
-- Shared rows are readable by every tenant's sends; a tenant writes only its own rows (shared rows are loaded by the migrator).
CREATE POLICY sms_template_read ON sms_templates FOR SELECT
  USING (tenant_id IS NULL OR tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY sms_template_write ON sms_templates FOR ALL
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- A delivery report names the BSP's message id, not the tenant: the DLR processor finds the row with app.dlr_lookup = 'on'
-- (set_config(..., true), SELECT only, SmsService.drainDlrs is the one caller), then binds that tenant for the update.
-- shortcut: a GUC like phone_routes.route_lookup instead of a SECURITY DEFINER owner role.
CREATE POLICY dlr_lookup ON outbound_messages FOR SELECT
  USING (channel = 'sms' AND current_setting('app.dlr_lookup', true) = 'on');
