SET lock_timeout = '5s';
-- IDC-002 (docs/adr/0018-consent-purposes-and-notices.md): consent purposes + versioned privacy notices.
-- Expand-only. consent_type stays THE purpose column (a separate 'purpose' column would duplicate it).
-- Existing rows keep notice_version/language NULL (captured before notices existed); no data migration.
CREATE TABLE privacy_notices (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  version         text NOT NULL CHECK (length(version) BETWEEN 1 AND 40),
  language        text NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$'),
  template_key    text NOT NULL,                 -- Nabd-owned market template this version was built from (CMP-01)
  body_md         text NOT NULL,
  mandatory_hash  text NOT NULL,                 -- SHA-256 of the locked mandatory clauses; a version whose hash differs from the template's is refused (IDC-004)
  effective_from  timestamptz NOT NULL DEFAULT now(),
  created_by      uuid REFERENCES staff(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, version, language)
);
SELECT enforce_tenant_rls('privacy_notices');

-- A published notice is immutable: a change is a new version row, so a consent always joins the text the patient saw.
CREATE FUNCTION privacy_notices_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'privacy_notices rows are immutable; publish a new version' USING ERRCODE = 'integrity_constraint_violation';
END $$;
CREATE TRIGGER trg_privacy_notices_immutable BEFORE UPDATE ON privacy_notices
  FOR EACH ROW EXECUTE FUNCTION privacy_notices_immutable();

ALTER TABLE consents
  ADD COLUMN notice_version    text,
  ADD COLUMN language          text,
  ADD COLUMN channel           text,
  ADD COLUMN witness_staff_id  uuid REFERENCES staff(id),
  ADD COLUMN evidence          jsonb;
ALTER TABLE consents ADD CONSTRAINT consents_channel_check
  CHECK (channel IS NULL OR channel IN ('DESK', 'WHATSAPP', 'ASSISTED', 'PORTAL'));
ALTER TABLE consents ADD CONSTRAINT consents_notice_pair_check
  CHECK ((notice_version IS NULL) = (language IS NULL));
-- MATCH SIMPLE: rows without a notice (legacy, withdrawals) skip the check; any row that names one joins exactly one notice.
ALTER TABLE consents ADD CONSTRAINT consents_notice_fk
  FOREIGN KEY (tenant_id, notice_version, language) REFERENCES privacy_notices (tenant_id, version, language);

ALTER TABLE consents DROP CONSTRAINT consents_consent_type_check;
ALTER TABLE consents ADD CONSTRAINT consents_consent_type_check
  CHECK (consent_type IN ('treatment', 'data_processing', 'messaging', 'guardian_access', 'marketing'));
