SET lock_timeout = '5s';
-- migration-lint: allow MIG-001 patient_proxies holds one row per caregiver-member pair (IDC-009, S17), tiny; the index builds in milliseconds and the repo forbids CONCURRENTLY
-- IDC-013 fix round 1 (T20-BUG-2): a clinic revoke consumes one step-up token. Its jti is stored on the proxy it revoked,
-- unique, so the same token cannot revoke a second proxy (same pattern as discount_approvals.pin_assertion_id, V2931).
ALTER TABLE patient_proxies ADD COLUMN revoke_step_up_id uuid;
CREATE UNIQUE INDEX uq_patient_proxies_revoke_step_up ON patient_proxies (revoke_step_up_id) WHERE revoke_step_up_id IS NOT NULL;
