SET lock_timeout = '5s';
-- COM-013: template versions. An edit (or a clinic rename) adds a NEW row for the same (tenant, name, language) with the next
-- version, submitted to Meta as its own template (<v1 meta_name>_v<n>), while the active row keeps sending. When Meta approves
-- the newer version it becomes the active one and the old row is superseded (kept for history). Exactly one active row per
-- name and language. paused_until holds sends while Meta pauses a template for quality; meta_category / category_changed_at
-- record Meta re-categorising a template (utility -> marketing changes the consent class of every send). example_values keeps
-- a custom template's examples so a rename can resubmit it without asking the clinic again.
ALTER TABLE whatsapp_templates
  ADD COLUMN version             int NOT NULL DEFAULT 1 CHECK (version >= 1),
  ADD COLUMN active              boolean NOT NULL DEFAULT true,
  ADD COLUMN superseded_by       uuid REFERENCES whatsapp_templates(id),
  ADD COLUMN meta_category       text,
  ADD COLUMN paused_until        timestamptz,
  ADD COLUMN category_changed_at timestamptz,
  ADD COLUMN example_values      text[];

ALTER TABLE whatsapp_templates DROP CONSTRAINT whatsapp_templates_tenant_id_name_language_key;
-- migration-lint: allow MIG-001 whatsapp_templates holds tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_active_once ON whatsapp_templates (tenant_id, name, language) WHERE active;
-- migration-lint: allow MIG-001 whatsapp_templates holds tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_version_once ON whatsapp_templates (tenant_id, name, language, version);
DROP INDEX whatsapp_templates_catalogue_once;
-- migration-lint: allow MIG-001 whatsapp_templates holds tens of rows per clinic and CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX whatsapp_templates_catalogue_once ON whatsapp_templates (tenant_id, catalogue_key, language)
  WHERE catalogue_key IS NOT NULL AND active;

-- Meta's template webhooks carry only Meta's id: this returns the owning clinic (and nothing else) so the processor can bind
-- that tenant and do the rest under RLS (same shape as notification_request_tenant, V1870).
CREATE FUNCTION whatsapp_template_tenant(p_meta_template_id text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT tenant_id FROM whatsapp_templates WHERE meta_template_id = p_meta_template_id $$;
REVOKE ALL ON FUNCTION whatsapp_template_tenant(text) FROM PUBLIC;

-- Clinic rename: active templates whose fixed header no longer matches the clinic name (the header is the name cut to 60
-- characters, TemplateRules.MAX_HEADER) and that have no newer version carrying the new header yet. Ids only, across tenants.
-- Clinics already cut over to the Comms Gateway are left out.
CREATE FUNCTION templates_with_stale_header(p_limit int) RETURNS TABLE (tenant_id uuid, id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT t.tenant_id, t.id FROM whatsapp_templates t JOIN tenants n ON n.id = t.tenant_id
   WHERE t.active AND t.status <> 'submitting' AND t.header_text <> btrim(left(btrim(n.name), 60))
     -- after the Comms cutover Core's copy is read-only (V2200 comms_legacy_guard): the Comms Gateway owns those templates
     AND NOT EXISTS (SELECT 1 FROM comms_cutover_flags f WHERE f.tenant_id = t.tenant_id AND f.legacy_readonly)
     AND NOT EXISTS (SELECT 1 FROM whatsapp_templates v
                      WHERE v.tenant_id = t.tenant_id AND v.name = t.name AND v.language = t.language
                        AND v.version > t.version AND v.header_text = btrim(left(btrim(n.name), 60)))
   ORDER BY t.tenant_id, t.id LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION templates_with_stale_header(int) FROM PUBLIC;

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION whatsapp_template_tenant(text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION templates_with_stale_header(int) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();

-- COM-013.2: a queued send knows its window end (from the notification request) so a pause can hold it until then.
ALTER TABLE whatsapp_messages ADD COLUMN not_after timestamptz;
