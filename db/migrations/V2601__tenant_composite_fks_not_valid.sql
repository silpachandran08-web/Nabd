SET lock_timeout = '5s';
-- OPS-005 (QA-28 / DP-18): a foreign-key check ignores RLS, so tenant A could attach tenant B's patient or doctor id to its own
-- appointment, visit, prescription ... Composite (tenant_id, x_id) foreign keys make the database refuse it. Expand step: the
-- unique parents, the new keys NOT VALID (checked for every new write at once, existing rows later in V2602) and the quarantine
-- table scripts/db/ops005-cross-tenant-precheck.sql fills. The single-column keys stay until a later release (contract step).
-- patients and staff already have UNIQUE (tenant_id, id) (uq_patients_tenant_id_id, uq_staff_tenant_id_id).

-- migration-lint: allow MIG-001 Flyway runs in a transaction so CONCURRENTLY cannot be used; departments is a small table and clinical_notes is built while the unique constraint is brand new (lock_timeout 5s)
CREATE UNIQUE INDEX uq_clinical_notes_tenant_id_id ON clinical_notes (tenant_id, id);
ALTER TABLE clinical_notes ADD CONSTRAINT uq_clinical_notes_tenant_id_id UNIQUE USING INDEX uq_clinical_notes_tenant_id_id;
CREATE UNIQUE INDEX uq_departments_tenant_id_id ON departments (tenant_id, id);
ALTER TABLE departments ADD CONSTRAINT uq_departments_tenant_id_id UNIQUE USING INDEX uq_departments_tenant_id_id;

-- Platform bookkeeping (no tenant_id column, so no RLS): rows the precheck found pointing across tenants. Must be empty to proceed.
CREATE TABLE cross_tenant_ref_quarantine (
  table_name       text        NOT NULL,
  column_name      text        NOT NULL,
  row_id           uuid        NOT NULL,
  child_tenant_id  uuid        NOT NULL,
  parent_tenant_id uuid        NOT NULL,
  found_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (table_name, column_name, row_id)
);

ALTER TABLE appointments ADD CONSTRAINT fk_appointments_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE queue_entries ADD CONSTRAINT fk_queue_entries_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE prescriptions ADD CONSTRAINT fk_prescriptions_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE clinical_notes ADD CONSTRAINT fk_clinical_notes_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE vitals ADD CONSTRAINT fk_vitals_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE waitlist_entries ADD CONSTRAINT fk_waitlist_entries_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE patient_allergies ADD CONSTRAINT fk_patient_allergies_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE chronic_conditions ADD CONSTRAINT fk_chronic_conditions_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE clinical_documents ADD CONSTRAINT fk_clinical_documents_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE vaccine_records ADD CONSTRAINT fk_vaccine_records_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE dental_chart_entries ADD CONSTRAINT fk_dental_chart_entries_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE consents ADD CONSTRAINT fk_consents_tenant_patient FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id) NOT VALID;
ALTER TABLE appointments ADD CONSTRAINT fk_appointments_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE queue_entries ADD CONSTRAINT fk_queue_entries_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE prescriptions ADD CONSTRAINT fk_prescriptions_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE clinical_notes ADD CONSTRAINT fk_clinical_notes_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE waitlist_entries ADD CONSTRAINT fk_waitlist_entries_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE clinical_documents ADD CONSTRAINT fk_clinical_documents_tenant_doctor FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE role_delegations ADD CONSTRAINT fk_role_delegations_tenant_staff FOREIGN KEY (tenant_id, staff_id) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE role_delegations ADD CONSTRAINT fk_role_delegations_tenant_granted_by FOREIGN KEY (tenant_id, granted_by) REFERENCES staff (tenant_id, id) NOT VALID;
ALTER TABLE queue_entries ADD CONSTRAINT fk_queue_entries_tenant_department FOREIGN KEY (tenant_id, department_id) REFERENCES departments (tenant_id, id) NOT VALID;
