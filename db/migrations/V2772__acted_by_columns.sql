SET lock_timeout = '5s';
-- IDC-011: who acted on a patient's behalf (a caregiver, a guardian or the desk ticking "on behalf of"). Nullable columns without
-- a default: no table rewrite. The composite FK is MATCH SIMPLE, so rows without an actor skip it. payments = invoice_payments.
ALTER TABLE appointments ADD COLUMN acted_by_patient_id uuid, ADD COLUMN acted_by_relation text;
ALTER TABLE queue_entries ADD COLUMN acted_by_patient_id uuid, ADD COLUMN acted_by_relation text;
ALTER TABLE invoice_payments ADD COLUMN acted_by_patient_id uuid, ADD COLUMN acted_by_relation text;
ALTER TABLE appointments ADD CONSTRAINT fk_appointments_acted_by FOREIGN KEY (tenant_id, acted_by_patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE queue_entries ADD CONSTRAINT fk_queue_entries_acted_by FOREIGN KEY (tenant_id, acted_by_patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE invoice_payments ADD CONSTRAINT fk_invoice_payments_acted_by FOREIGN KEY (tenant_id, acted_by_patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT fk_appointments_acted_by;
ALTER TABLE queue_entries VALIDATE CONSTRAINT fk_queue_entries_acted_by;
ALTER TABLE invoice_payments VALIDATE CONSTRAINT fk_invoice_payments_acted_by;
