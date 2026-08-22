-- NB-186: OTC sale without a patient record. A counter sale creates neither a patient record
-- nor a queue token — invoices.patient_id/doctor_id/queue_entry_id become optional so a walk-in
-- purchase can post straight to invoices with no upstream row to point at.
ALTER TABLE invoices ALTER COLUMN queue_entry_id DROP NOT NULL;
ALTER TABLE invoices ALTER COLUMN patient_id DROP NOT NULL;
ALTER TABLE invoices ALTER COLUMN doctor_id DROP NOT NULL;
