-- NUR-022: GET /v1/clinical/vitals/patient/{patientId}/previous reads a patient's vitals newest first.
-- Index only; vitals keeps the RLS policy from V21 (tenant_id is the leading column so the policy filter uses it too).
CREATE INDEX IF NOT EXISTS idx_vitals_patient_recorded ON vitals (tenant_id, patient_id, recorded_at DESC);
