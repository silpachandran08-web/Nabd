-- V69 (DOC-027): doctor-authored consultation note templates.
--
-- Every template is written by one doctor and visible only to that doctor (doctor_id is NOT NULL; the application
-- scopes every read and write to the caller, RLS isolates the tenant). There is NO seeded clinical text: the table
-- starts empty and holds only what a doctor typed. Tags are free labels the doctor defines (jsonb array of strings).
-- Expand-only, written to be safe to re-run.

CREATE TABLE IF NOT EXISTS note_templates (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  doctor_id   uuid NOT NULL REFERENCES staff(id),
  name        text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
  subjective  text NOT NULL DEFAULT '',
  objective   text NOT NULL DEFAULT '',
  assessment  text NOT NULL DEFAULT '',
  plan        text NOT NULL DEFAULT '',
  tags        jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tags) = 'array'),
  favourite   boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_note_templates_doctor
  ON note_templates (tenant_id, doctor_id, favourite DESC, name);

DROP TRIGGER IF EXISTS trg_note_templates_updated_at ON note_templates;
CREATE TRIGGER trg_note_templates_updated_at BEFORE UPDATE ON note_templates
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE note_templates ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'note_templates' AND policyname = 'tenant_isolation') THEN
    CREATE POLICY tenant_isolation ON note_templates
      USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
  END IF;
END $$;
