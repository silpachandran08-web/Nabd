-- IDC-001: own-patients scope probes appointments OR queue_entries by (tenant, doctor, patient).
CREATE INDEX IF NOT EXISTS idx_appointments_tenant_doctor_patient ON appointments (tenant_id, doctor_id, patient_id);
CREATE INDEX IF NOT EXISTS idx_queue_entries_tenant_doctor_patient ON queue_entries (tenant_id, doctor_id, patient_id);
