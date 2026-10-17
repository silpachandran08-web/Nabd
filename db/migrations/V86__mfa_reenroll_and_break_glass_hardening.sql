-- MF-01 / MF-02 (docs/engineering/06 §2): MFA re-enrollment and break-glass hardening.

-- QA-04: enrollment used to write the new secret straight over mfa_secret_enc, so an abandoned
-- re-enroll locked the user out and anyone holding a session could swap the second factor. The new
-- secret now waits here until /mfa/confirm proves a code from it, and only then replaces the active one.
ALTER TABLE staff ADD COLUMN mfa_pending_secret_enc bytea;

-- QA-04: confirming a (re-)enrollment revokes every earlier recovery code, so codes from a previous
-- enrollment (possibly leaked) stop working. Kept as a column rather than a DELETE so the history stays.
ALTER TABLE mfa_recovery_codes ADD COLUMN revoked_at timestamptz;
DROP INDEX idx_mfa_recovery_codes_staff;
CREATE INDEX idx_mfa_recovery_codes_staff ON mfa_recovery_codes (staff_id) WHERE used_at IS NULL AND revoked_at IS NULL;

-- F-02 / QA-02 / QA-26: break-glass elevation is bound to the one session family minted at activation
-- (so ending the grant can revoke exactly that session), and every grant gets a post-hoc review by
-- someone holding emergency:approve other than the activator.
ALTER TABLE break_glass_grants
  ADD COLUMN session_family_id uuid,
  ADD COLUMN deactivated_by    uuid REFERENCES staff(id),
  ADD COLUMN reviewed_at       timestamptz,
  ADD COLUMN reviewed_by       uuid REFERENCES staff(id),
  ADD COLUMN review_notes      text;
ALTER TABLE sessions DROP CONSTRAINT sessions_revoked_reason_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_revoked_reason_check
  CHECK (revoked_reason IN ('logout','rotated','reuse_detected','suspended','admin_revoke','break_glass_ended'));
CREATE INDEX idx_break_glass_unreviewed ON break_glass_grants (tenant_id, activated_at DESC) WHERE reviewed_at IS NULL;

-- Activation now needs the dedicated emergency:create permission and review needs emergency:approve.
-- Every existing built-in Owner role gets the full "emergency" module, matching what provisioning now
-- seeds for new clinics, so owners can grant activation to clinician roles and review grants. Same
-- backfill shape as V38 (built-in roles can't be edited through the API).
UPDATE roles
SET grants = grants || '[{"module":"emergency","view":true,"create":true,"edit":true,"delete":true,"approve":true,"refundDiscount":true,"export":true}]'::jsonb
WHERE built_in = true
  AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(grants) g WHERE g->>'module' = 'emergency'
  );
