-- E17 phase 2: NB-190 (template library, approval state, quality rating), NB-191 (variable-fill-only
-- sends), NB-198 (clinic-branded messages).
--
-- Templates are per clinic. Until each clinic has its own WhatsApp Business Account (NB-188), they
-- are all registered on Nabd's shared account, so meta_name is prefixed with the tenant slug to stay
-- unique there. NB-198 on a shared number: the clinic's name is baked into every template's header
-- as fixed text at submission — Meta approves it, the patient sees it, and no send path can alter it.
-- ponytail: a clinic rename doesn't touch already-approved headers; resubmit templates if that matters.
CREATE TABLE whatsapp_templates (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  name              text NOT NULL,              -- what the clinic and calling code refer to
  meta_name         text NOT NULL UNIQUE,       -- what Meta knows it as: <tenant slug>_<name>
  language          text NOT NULL,
  category          text NOT NULL CHECK (category IN ('UTILITY', 'MARKETING')),
  header_text       text NOT NULL,              -- clinic name at submission time (NB-198)
  body              text NOT NULL,              -- fixed text with {{1}}..{{n}} slots; never sent by callers (NB-191)
  param_count       int NOT NULL CHECK (param_count >= 0),
  -- pending: with Meta for review. approved/flagged: sendable (flagged = quality warning, may pause
  -- next). paused/disabled: Meta stopped it for quality. rejected: see rejection_reason, edit to resubmit.
  status            text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'approved', 'flagged', 'paused', 'disabled', 'rejected')),
  rejection_reason  text,
  quality_rating    text NOT NULL DEFAULT 'UNKNOWN' CHECK (quality_rating IN ('GREEN', 'YELLOW', 'RED', 'UNKNOWN')),
  meta_template_id  text UNIQUE,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name, language)
);
CREATE TRIGGER trg_whatsapp_templates_updated_at BEFORE UPDATE ON whatsapp_templates
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE whatsapp_templates ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_templates
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE whatsapp_messages ADD COLUMN template_id uuid REFERENCES whatsapp_templates(id);

-- Meta's template webhooks carry only Meta's template id — same cross-tenant shape as
-- apply_whatsapp_status (V46). Either argument may be NULL: a status event leaves quality alone
-- and vice versa. The reason is cleared when a template leaves 'rejected'.
CREATE FUNCTION apply_whatsapp_template_update(p_meta_template_id text, p_status text, p_reason text, p_quality text)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE whatsapp_templates
  SET status = COALESCE(p_status, status),
      rejection_reason = CASE WHEN COALESCE(p_status, status) = 'rejected' THEN COALESCE(p_reason, rejection_reason) END,
      quality_rating = COALESCE(p_quality, quality_rating)
  WHERE meta_template_id = p_meta_template_id;
$$;
REVOKE ALL ON FUNCTION apply_whatsapp_template_update(text, text, text, text) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION apply_whatsapp_template_update(text,text,text,text) TO <app_role>;
