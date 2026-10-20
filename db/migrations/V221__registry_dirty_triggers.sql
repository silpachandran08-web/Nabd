SET lock_timeout = '5s';
-- OPS-007: every write that can change a registry field marks the patient dirty (same transaction).
-- Cross-patient effect (duplicate_flag when someone else's phone/name/dob changes) is repaired by the
-- nightly reconciliation, not here.
CREATE FUNCTION registry_mark_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  r record := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  pid uuid;
BEGIN
  pid := CASE TG_ARGV[0]
    WHEN 'id' THEN (to_jsonb(r) ->> 'id')::uuid
    WHEN 'instance_id' THEN (SELECT patient_id FROM package_instances WHERE id = (to_jsonb(r) ->> 'instance_id')::uuid)
    ELSE (to_jsonb(r) ->> 'patient_id')::uuid END;
  IF pid IS NOT NULL THEN
    INSERT INTO registry_dirty (tenant_id, patient_id) VALUES (r.tenant_id, pid) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OF name, phone, dob, mrn, status, guardian_id ON patients
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON queue_entries
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON appointments
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON invoices
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON chronic_conditions
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON patient_allergies
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON package_instances
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('patient_id');
CREATE TRIGGER trg_registry_dirty AFTER INSERT OR UPDATE OR DELETE ON package_instance_items
  FOR EACH ROW EXECUTE FUNCTION registry_mark_dirty('instance_id');
