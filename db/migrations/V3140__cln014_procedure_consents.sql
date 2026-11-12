SET lock_timeout = '5s';
-- CLN-014: signed procedure consent. A consent form is clinic data: one row per (clinic, charge code, language, version); a new
-- wording is a new version, never an edit. tenant_id NULL rows are Nabd starter PLACEHOLDERS (placeholder = true): their text only
-- says that the clinic must supply reviewed wording, and the service refuses to capture a signature against them.
CREATE TABLE consent_templates (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid REFERENCES tenants(id),
  charge_code text NOT NULL CHECK (length(charge_code) BETWEEN 1 AND 64),
  lang        text NOT NULL CHECK (lang IN ('en', 'hi', 'kn', 'te', 'ar')),
  version     integer NOT NULL CHECK (version > 0),
  title       text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  body_html   text NOT NULL CHECK (length(body_html) BETWEEN 1 AND 100000),
  active      boolean NOT NULL DEFAULT true,
  placeholder boolean NOT NULL DEFAULT false,
  created_by  uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT consent_templates_placeholder_is_starter CHECK (NOT placeholder OR tenant_id IS NULL)
);
CREATE UNIQUE INDEX uq_consent_templates_version ON consent_templates (tenant_id, charge_code, lang, version) NULLS NOT DISTINCT;
-- Starter PLACEHOLDERS (CLN-014.4). Not consent wording: the clinic's clinician and counsel must author the real text (per market,
-- CSO review). Seeded only for the starter catalogue's procedure codes (V2910): chemical peel, laser, minor procedure. Filler, ear
-- syringing and cautery have no starter charge code yet, so no placeholder.
INSERT INTO consent_templates (tenant_id, charge_code, lang, version, title, body_html, active, placeholder)
SELECT NULL, c.code, l.lang, 1, 'PLACEHOLDER - ' || c.name || ' consent (not for use)',
       '<p>PLACEHOLDER - NOT REVIEWED. This is not a consent form.</p><p>The clinic must add its own ' || c.name ||
       ' consent wording' || CASE WHEN l.lang = 'hi' THEN ' in Hindi' ELSE '' END ||
       ', including the procedure-specific risks, approved by its clinician and legal counsel, before a patient can sign.</p>',
       true, true
  FROM (VALUES ('PEEL', 'Chemical peel'), ('LASER', 'Laser session'), ('PROC', 'Minor procedure')) AS c(code, name)
 CROSS JOIN (VALUES ('en'), ('hi')) AS l(lang);
-- seeded before RLS is forced: a NULL-tenant row fails the own-tenant INSERT policy
SELECT enforce_tenant_rls('consent_templates', 'read');

CREATE FUNCTION consent_templates_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- only retiring a version (active true -> false) is allowed; the wording a patient signed never changes
  IF TG_OP = 'UPDATE' AND OLD.active AND NOT NEW.active
     AND (NEW.id, NEW.tenant_id, NEW.charge_code, NEW.lang, NEW.version, NEW.title, NEW.body_html, NEW.placeholder)
         IS NOT DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.charge_code, OLD.lang, OLD.version, OLD.title, OLD.body_html, OLD.placeholder) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'consent_templates rows are immutable: add a new version' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_consent_templates_immutable BEFORE UPDATE OR DELETE ON consent_templates
  FOR EACH ROW EXECUTE FUNCTION consent_templates_immutable();

-- One consent per procedure order (a withdrawal is final for that order: a new order needs a new consent).
CREATE TABLE procedure_consents (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  patient_id           uuid NOT NULL,
  procedure_order_id   uuid NOT NULL REFERENCES procedure_orders(id),
  template_id          uuid NOT NULL REFERENCES consent_templates(id),
  template_version     integer NOT NULL,
  lang                 text NOT NULL,
  signer_kind          text NOT NULL CHECK (signer_kind IN ('patient', 'guardian')),
  guardian_id          uuid,
  signer_name          text NOT NULL,
  witness_staff_id     uuid NOT NULL REFERENCES staff(id),
  signature_file_id    uuid NOT NULL REFERENCES file_objects(id),
  pdf_file_id          uuid NOT NULL REFERENCES file_objects(id),
  pdf_sha256           text NOT NULL CHECK (pdf_sha256 ~ '^[0-9a-f]{64}$'),
  status               text NOT NULL DEFAULT 'signed' CHECK (status IN ('signed', 'withdrawn')),
  signed_by            uuid NOT NULL REFERENCES staff(id),
  signed_at            timestamptz NOT NULL DEFAULT now(),
  withdrawn_at         timestamptz,
  withdrawn_by         uuid REFERENCES staff(id),
  withdraw_reason      text,
  CONSTRAINT procedure_consents_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT procedure_consents_guardian_fk FOREIGN KEY (tenant_id, guardian_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT procedure_consents_guardian CHECK ((signer_kind = 'guardian') = (guardian_id IS NOT NULL)),
  CONSTRAINT procedure_consents_withdrawn CHECK ((status = 'withdrawn') = (withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL AND withdraw_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uq_procedure_consents_order ON procedure_consents (tenant_id, procedure_order_id);
CREATE INDEX idx_procedure_consents_patient ON procedure_consents (tenant_id, patient_id);
SELECT enforce_tenant_rls('procedure_consents');

CREATE FUNCTION procedure_consents_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.status = 'signed' AND NEW.status = 'withdrawn'
     AND (NEW.id, NEW.tenant_id, NEW.patient_id, NEW.procedure_order_id, NEW.template_id, NEW.template_version, NEW.lang, NEW.signer_kind,
          NEW.guardian_id, NEW.signer_name, NEW.witness_staff_id, NEW.signature_file_id, NEW.pdf_file_id, NEW.pdf_sha256,
          NEW.signed_by, NEW.signed_at)
         IS NOT DISTINCT FROM
         (OLD.id, OLD.tenant_id, OLD.patient_id, OLD.procedure_order_id, OLD.template_id, OLD.template_version, OLD.lang, OLD.signer_kind,
          OLD.guardian_id, OLD.signer_name, OLD.witness_staff_id, OLD.signature_file_id, OLD.pdf_file_id, OLD.pdf_sha256,
          OLD.signed_by, OLD.signed_at) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'procedure_consents rows are append-only (only signed -> withdrawn)' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_procedure_consents_append_only BEFORE UPDATE OR DELETE ON procedure_consents
  FOR EACH ROW EXECUTE FUNCTION procedure_consents_append_only();

-- A withdrawal is recorded on the order too, so it also covers a typed-name consent (NB-119) for a procedure with no form yet.
ALTER TABLE procedure_orders
  ADD COLUMN consent_withdrawn_at timestamptz,
  ADD COLUMN consent_withdrawn_by uuid REFERENCES staff(id),
  ADD COLUMN consent_withdraw_reason text;

-- Backstop for the service check: an order moves to prepped/completed only with a recorded, non-withdrawn consent, and when the
-- clinic has an active consent form for the charge code, only with a signed procedure_consents row.
CREATE FUNCTION procedure_orders_consent_gate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('prepped', 'completed') AND NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.consent_signed_at IS NULL OR NEW.consent_withdrawn_at IS NOT NULL
       OR (EXISTS (SELECT 1 FROM consent_templates t
                    WHERE t.tenant_id = NEW.tenant_id AND t.charge_code = NEW.charge_code AND t.active)
           AND NOT EXISTS (SELECT 1 FROM procedure_consents c
                            WHERE c.tenant_id = NEW.tenant_id AND c.procedure_order_id = NEW.id AND c.status = 'signed')) THEN
      RAISE EXCEPTION 'procedure_orders_consent_required: no valid consent for this procedure'
        USING ERRCODE = 'check_violation', CONSTRAINT = 'procedure_orders_consent_required';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_procedure_orders_consent_gate BEFORE UPDATE OF status ON procedure_orders
  FOR EACH ROW EXECUTE FUNCTION procedure_orders_consent_gate();
