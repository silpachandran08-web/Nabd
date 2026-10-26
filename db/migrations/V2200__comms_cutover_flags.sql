SET lock_timeout = '5s';
-- COM-008: per-tenant switch between the in-process WhatsApp sender and the remote Comms Gateway.
-- remote = true: Core no longer sends for the tenant (NotificationService.deliver returns, the gateway claims the request).
-- legacy_readonly = true: set only after every tenant is cut over; V46/V47 tables then refuse writes (dropped one release later).
CREATE TABLE comms_cutover_flags (
  tenant_id       uuid PRIMARY KEY REFERENCES tenants(id),
  remote          boolean NOT NULL DEFAULT false,
  legacy_readonly boolean NOT NULL DEFAULT false CHECK (NOT legacy_readonly OR remote),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE comms_cutover_flags ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON comms_cutover_flags
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
SELECT enforce_tenant_rls('comms_cutover_flags');

CREATE FUNCTION comms_legacy_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM comms_cutover_flags WHERE tenant_id = NEW.tenant_id AND legacy_readonly) THEN
    RAISE EXCEPTION 'table % is read-only after the Comms cutover', TG_TABLE_NAME USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_comms_readonly BEFORE INSERT OR UPDATE ON whatsapp_messages FOR EACH ROW EXECUTE FUNCTION comms_legacy_guard();
CREATE TRIGGER trg_comms_readonly BEFORE INSERT OR UPDATE ON whatsapp_templates FOR EACH ROW EXECUTE FUNCTION comms_legacy_guard();
CREATE TRIGGER trg_comms_readonly BEFORE INSERT OR UPDATE ON whatsapp_inbound_events FOR EACH ROW EXECUTE FUNCTION comms_legacy_guard();
