-- OPS-001 (DP-05): paediatric dose weight lookup (VitalsRepository.findLatestWeight, a safety path) seq-scanned vitals.
CREATE INDEX IF NOT EXISTS idx_vitals_patient_weight ON vitals (tenant_id, patient_id, weight_recorded_at DESC NULLS LAST) WHERE weight_kg IS NOT NULL;
