SET lock_timeout = '5s';
-- PHL-045: sample collection. One sample row per unique container collected from an order. Accession numbers are unique
-- per tenant per day sequence (see LabCollectionService.nextAccession). Status tracks the sample lifecycle from collection
-- through processing and storage. non_fasting marks samples collected with less fasting than the tests require.
CREATE TABLE lab_samples (
  id                   uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id            uuid NOT NULL REFERENCES tenants (id),
  order_id             uuid NOT NULL REFERENCES lab_orders (id),
  accession            text NOT NULL,
  container            text NOT NULL,
  sample_type          text,
  collected_by         uuid REFERENCES staff (id),
  collected_at         timestamptz NOT NULL,
  fasting_hours        int CHECK (fasting_hours >= 0),
  non_fasting          boolean NOT NULL DEFAULT false,
  status               text NOT NULL DEFAULT 'collected' CHECK (status IN ('collected', 'in_transit', 'received', 'rejected', 'processed', 'stored', 'disposed')),
  received_by          uuid REFERENCES staff (id),
  received_at          timestamptz,
  rejection_reason     text CHECK (length(rejection_reason) <= 500),
  location             text,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, accession)
);
CREATE INDEX idx_lab_samples_order ON lab_samples (tenant_id, order_id);
CREATE INDEX idx_lab_samples_accession ON lab_samples (tenant_id, accession);
CREATE TRIGGER trg_lab_samples_updated_at BEFORE UPDATE ON lab_samples
  FOR EACH ROW EXECUTE FUNCTION update_timestamp();
SELECT enforce_tenant_rls('lab_samples');

-- Link samples to their order items. One sample can serve multiple tests (e.g., one EDTA tube for CBC + HbA1c).
CREATE TABLE lab_sample_items (
  id                   uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id            uuid NOT NULL REFERENCES tenants (id),
  sample_id            uuid NOT NULL REFERENCES lab_samples (id) ON DELETE CASCADE,
  order_item_id        uuid NOT NULL REFERENCES lab_order_items (id)
);
CREATE INDEX idx_lab_sample_items_sample ON lab_sample_items (tenant_id, sample_id);
CREATE INDEX idx_lab_sample_items_item ON lab_sample_items (tenant_id, order_item_id);
SELECT enforce_tenant_rls('lab_sample_items');
