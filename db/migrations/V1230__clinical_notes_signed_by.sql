-- CLN-006 (QA-18): a signed visit note names its signer and is final at the database level.
-- Amendments go to note_amendments only (amended_by already exists there).
SET lock_timeout = '5s';

-- Composite FKs need a unique key on (tenant_id, id). Guarded: another story may add the same key first.
DO $$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['staff', 'patients'] LOOP
    IF NOT EXISTS (
      SELECT 1 FROM pg_index i
      WHERE i.indrelid = t::regclass AND i.indisunique AND i.indpred IS NULL
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
             FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
             JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum) = ARRAY['tenant_id', 'id']
    ) THEN
      EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id, id)', t, 'uq_' || t || '_tenant_id_id');
    END IF;
  END LOOP;
END $$;

ALTER TABLE clinical_notes ADD COLUMN IF NOT EXISTS signed_by uuid;
ALTER TABLE clinical_notes ADD CONSTRAINT fk_clinical_notes_signed_by
  FOREIGN KEY (tenant_id, signed_by) REFERENCES staff (tenant_id, id);

-- Backfill: before this story only the visit doctor's own session could reasonably sign; use doctor_id.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    UPDATE clinical_notes SET signed_by = doctor_id WHERE tenant_id = t.id AND status = 'signed' AND signed_by IS NULL;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

-- A signed note is final: any UPDATE of a signed row is refused (same backstop as V51 for prescriptions).
CREATE OR REPLACE FUNCTION clinical_notes_signed_is_final() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'signed' THEN
    RAISE EXCEPTION 'clinical note is signed and cannot be modified; add an amendment instead'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS clinical_notes_signed_is_final ON clinical_notes;
CREATE TRIGGER clinical_notes_signed_is_final BEFORE UPDATE ON clinical_notes
  FOR EACH ROW EXECUTE FUNCTION clinical_notes_signed_is_final();

-- clinical_notes already has ENABLE + FORCE RLS with per-command policies (V150/V211); nothing to add.
