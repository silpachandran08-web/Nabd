SET lock_timeout = '5s';
-- PLT-041: every open attempt of a patient's secure link is a phi_read_log row with actor_type 'patient_link' (actor_id = the link).
-- outcome says what happened; device_family is the coarse device class parsed from the User-Agent (the raw string is never stored).
-- Both stay NULL for staff and operator reads. The columns are not part of the batch hash (phi_read_row_text): rows are append-only
-- by trigger, so they cannot change after the fact either way.
ALTER TABLE phi_read_log ADD COLUMN outcome text, ADD COLUMN device_family text;
ALTER TABLE phi_read_log ADD CONSTRAINT phi_read_log_outcome_check CHECK (outcome IS NULL OR outcome IN ('otp_ok', 'otp_failed', 'expired', 'revoked')) NOT VALID;
ALTER TABLE phi_read_log VALIDATE CONSTRAINT phi_read_log_outcome_check;
ALTER TABLE phi_read_log ADD CONSTRAINT phi_read_log_device_family_check CHECK (device_family IS NULL OR length(device_family) <= 20) NOT VALID;
ALTER TABLE phi_read_log VALIDATE CONSTRAINT phi_read_log_device_family_check;
-- the owner's list of link opens by patient or recency
-- migration-lint: allow MIG-001 phi_read_log is partitioned; CONCURRENTLY is not possible on a partitioned parent and the table holds only reads of the last months
CREATE INDEX idx_phi_read_log_link_opens ON phi_read_log (tenant_id, read_at DESC) WHERE actor_type = 'patient_link';
