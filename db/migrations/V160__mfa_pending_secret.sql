-- PLT-004: re-enrolment keeps the active secret until the new one is confirmed; recovery-code sets can be revoked;
-- an owner reset forces enrolment at next sign-in.
ALTER TABLE staff
  ADD COLUMN mfa_pending_secret_enc bytea,
  ADD COLUMN mfa_pending_created_at timestamptz,
  ADD COLUMN mfa_reset_required boolean NOT NULL DEFAULT false;

ALTER TABLE mfa_recovery_codes ADD COLUMN revoked_at timestamptz;
CREATE INDEX idx_mfa_recovery_codes_live ON mfa_recovery_codes (staff_id) WHERE used_at IS NULL AND revoked_at IS NULL;
-- RLS: V211 (runs later) already forces per-command policies with WITH CHECK on mfa_recovery_codes; enforce_tenant_rls() does not exist yet at V160.

ALTER TABLE master.operators
  ADD COLUMN mfa_pending_secret_enc bytea,
  ADD COLUMN mfa_pending_created_at timestamptz;
