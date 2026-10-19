-- IDC-001: the clinical read gate (ReadScope.gate) is NOT EXISTS (withdrawn data_processing consent); this partial
-- index keeps that probe an index lookup. Withdrawals are rare, so the index stays tiny.
CREATE INDEX IF NOT EXISTS idx_consents_dp_withdrawn
    ON consents (tenant_id, patient_id)
    WHERE consent_type = 'data_processing' AND withdrawn_at IS NOT NULL;
