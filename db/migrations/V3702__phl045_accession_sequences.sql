SET lock_timeout = '5s';
-- PHL-045: accession number sequences, one per tenant per day. Ensures uniqueness under concurrent collection.
-- The next_number is incremented atomically on each sample collection to generate unique accessions.
CREATE TABLE lab_accession_sequences (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  sequence_date   date NOT NULL,
  next_number     int NOT NULL DEFAULT 1,
  UNIQUE (tenant_id, sequence_date)
);
CREATE INDEX idx_lab_accession_sequences_tenant_date ON lab_accession_sequences (tenant_id, sequence_date);
SELECT enforce_tenant_rls('lab_accession_sequences');
