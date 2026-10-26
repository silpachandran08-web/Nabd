SET lock_timeout = '5s';
-- OPS-006 (DP-19 / DP-23): every synced table carries a monotonic change_seq (one global sequence, assigned by a BEFORE trigger)
-- and an updated_at, so ETag/304, the SSE "apply only if v > current" gate and delta sync become possible; and append-heavy
-- tables get time-ordered UUID v7 ids. Expand step only, no table rewrite: columns are nullable / carry a constant-time default.
-- change_seq stays NULL on rows nobody has touched since this migration: readers treat NULL as 0 (a delta consumer starts from a
-- full read). It is not backfilled: signed prescriptions are immutable (trg_prescriptions_signed_final) and a bulk UPDATE would
-- fire trg_registry_dirty for every patient. NOT NULL is therefore not set.
CREATE SEQUENCE change_seq_global;

CREATE FUNCTION set_change_seq() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.change_seq := nextval('change_seq_global');
  IF TG_OP = 'UPDATE' AND coalesce(current_setting('nabd.skip_updated_at', true), '') <> 'on' THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END;
$$;

-- RFC 9562 UUID v7: 48-bit unix-ms timestamp, version 7, random rest (the random part comes from gen_random_uuid()).
CREATE FUNCTION uuid_v7() RETURNS uuid LANGUAGE sql VOLATILE AS $$
  SELECT encode(
    set_bit(set_bit(overlay(uuid_send(gen_random_uuid()) PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3) FROM 1 FOR 6), 52, 1), 53, 1),
    'hex')::uuid
$$;

ALTER TABLE queue_entries ADD COLUMN change_seq bigint;
ALTER TABLE appointments ADD COLUMN change_seq bigint;
ALTER TABLE invoices ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE prescriptions ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE vitals ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE waitlist_entries ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE procedure_orders ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE administration_orders ADD COLUMN change_seq bigint, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE patients ADD COLUMN change_seq bigint;

-- migration-lint: allow MIG-001 Flyway runs in a transaction so CONCURRENTLY cannot be used; the indexes are partial (only touched rows) and built under lock_timeout 5s
CREATE INDEX idx_queue_entries_tenant_change_seq ON queue_entries (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_appointments_tenant_change_seq ON appointments (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_invoices_tenant_change_seq ON invoices (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_prescriptions_tenant_change_seq ON prescriptions (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_vitals_tenant_change_seq ON vitals (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_waitlist_entries_tenant_change_seq ON waitlist_entries (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_procedure_orders_tenant_change_seq ON procedure_orders (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_administration_orders_tenant_change_seq ON administration_orders (tenant_id, change_seq) WHERE change_seq IS NOT NULL;
CREATE INDEX idx_patients_tenant_change_seq ON patients (tenant_id, change_seq) WHERE change_seq IS NOT NULL;

CREATE TRIGGER trg_queue_entries_change_seq BEFORE INSERT OR UPDATE ON queue_entries FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_appointments_change_seq BEFORE INSERT OR UPDATE ON appointments FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_invoices_change_seq BEFORE INSERT OR UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_prescriptions_change_seq BEFORE INSERT OR UPDATE ON prescriptions FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_vitals_change_seq BEFORE INSERT OR UPDATE ON vitals FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_waitlist_entries_change_seq BEFORE INSERT OR UPDATE ON waitlist_entries FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_procedure_orders_change_seq BEFORE INSERT OR UPDATE ON procedure_orders FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_administration_orders_change_seq BEFORE INSERT OR UPDATE ON administration_orders FOR EACH ROW EXECUTE FUNCTION set_change_seq();
CREATE TRIGGER trg_patients_change_seq BEFORE INSERT OR UPDATE ON patients FOR EACH ROW EXECUTE FUNCTION set_change_seq();

-- Time-ordered ids for the append-heavy tables (Java inserts use shared.UuidV7; this default covers every other writer).
ALTER TABLE queue_entries ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE invoices ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE invoice_line_items ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE appointments ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE vitals ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE prescriptions ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE prescription_items ALTER COLUMN id SET DEFAULT uuid_v7();
ALTER TABLE sessions ALTER COLUMN id SET DEFAULT uuid_v7();
