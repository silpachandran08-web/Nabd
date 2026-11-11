SET lock_timeout = '5s';
-- COM-013: Core keeps versions of a template (V3100): a new version is in review while the active one keeps sending, and the
-- approved newer one takes over. The Comms copy carries version + active so two versions of one name can coexist and only the
-- active one is offered or sent; paused_until and meta_category mirror Core's pause hold and Meta's re-categorisation.
ALTER TABLE whatsapp_templates
  ADD COLUMN version       int NOT NULL DEFAULT 1,
  ADD COLUMN active        boolean NOT NULL DEFAULT true,
  ADD COLUMN paused_until  timestamptz,
  ADD COLUMN meta_category text;
ALTER TABLE whatsapp_templates DROP CONSTRAINT whatsapp_templates_tenant_id_name_language_key;
-- migration-lint: allow MIG-001 tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_version_once ON whatsapp_templates (tenant_id, name, language, version);
-- migration-lint: allow MIG-001 tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_active_once ON whatsapp_templates (tenant_id, name, language) WHERE active;
DROP INDEX whatsapp_templates_catalogue_once;
-- migration-lint: allow MIG-001 tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_catalogue_once ON whatsapp_templates (tenant_id, catalogue_key, language)
  WHERE catalogue_key IS NOT NULL AND active;
