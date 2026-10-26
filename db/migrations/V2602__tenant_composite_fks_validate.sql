SET lock_timeout = '3s';
-- OPS-005: validate the composite keys of V2601. VALIDATE CONSTRAINT takes only SHARE UPDATE EXCLUSIVE, so reads and writes
-- continue; each statement is its own step with lock_timeout 3 s. Run scripts/db/ops005-cross-tenant-precheck.sql first:
-- a cross-tenant row makes the statement (and this migration) fail instead of being silently accepted.
ALTER TABLE appointments VALIDATE CONSTRAINT fk_appointments_tenant_patient;
ALTER TABLE queue_entries VALIDATE CONSTRAINT fk_queue_entries_tenant_patient;
ALTER TABLE prescriptions VALIDATE CONSTRAINT fk_prescriptions_tenant_patient;
ALTER TABLE invoices VALIDATE CONSTRAINT fk_invoices_tenant_patient;
ALTER TABLE clinical_notes VALIDATE CONSTRAINT fk_clinical_notes_tenant_patient;
ALTER TABLE vitals VALIDATE CONSTRAINT fk_vitals_tenant_patient;
ALTER TABLE waitlist_entries VALIDATE CONSTRAINT fk_waitlist_entries_tenant_patient;
ALTER TABLE patient_allergies VALIDATE CONSTRAINT fk_patient_allergies_tenant_patient;
ALTER TABLE chronic_conditions VALIDATE CONSTRAINT fk_chronic_conditions_tenant_patient;
ALTER TABLE clinical_documents VALIDATE CONSTRAINT fk_clinical_documents_tenant_patient;
ALTER TABLE vaccine_records VALIDATE CONSTRAINT fk_vaccine_records_tenant_patient;
ALTER TABLE dental_chart_entries VALIDATE CONSTRAINT fk_dental_chart_entries_tenant_patient;
ALTER TABLE consents VALIDATE CONSTRAINT fk_consents_tenant_patient;
ALTER TABLE appointments VALIDATE CONSTRAINT fk_appointments_tenant_doctor;
ALTER TABLE queue_entries VALIDATE CONSTRAINT fk_queue_entries_tenant_doctor;
ALTER TABLE prescriptions VALIDATE CONSTRAINT fk_prescriptions_tenant_doctor;
ALTER TABLE invoices VALIDATE CONSTRAINT fk_invoices_tenant_doctor;
ALTER TABLE clinical_notes VALIDATE CONSTRAINT fk_clinical_notes_tenant_doctor;
ALTER TABLE waitlist_entries VALIDATE CONSTRAINT fk_waitlist_entries_tenant_doctor;
ALTER TABLE clinical_documents VALIDATE CONSTRAINT fk_clinical_documents_tenant_doctor;
ALTER TABLE role_delegations VALIDATE CONSTRAINT fk_role_delegations_tenant_staff;
ALTER TABLE role_delegations VALIDATE CONSTRAINT fk_role_delegations_tenant_granted_by;
ALTER TABLE queue_entries VALIDATE CONSTRAINT fk_queue_entries_tenant_department;
