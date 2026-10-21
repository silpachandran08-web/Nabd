-- PLT-052 passing sample: every column it introduces is tagged; master schema and partitions are out of scope.
SET lock_timeout = '5s';
CREATE TABLE IF NOT EXISTS registry_dirty (
  tenant_id  uuid NOT NULL,
  patient_id uuid NOT NULL,
  PRIMARY KEY (tenant_id, patient_id)
);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS mrn text, ADD COLUMN IF NOT EXISTS dob date,
  ADD CONSTRAINT patients_dob_past CHECK (dob <= now()) NOT VALID;
CREATE TABLE master.operator_notes (id uuid PRIMARY KEY, body text);
CREATE TABLE IF NOT EXISTS phi_read_log_y2027m02 PARTITION OF phi_read_log FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
