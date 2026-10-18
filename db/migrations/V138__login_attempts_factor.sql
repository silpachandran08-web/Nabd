-- PLT-003: one failed-attempt counter across PIN / OTP / TOTP / unlock / step-up, TOTP replay guard,
-- and PIN policy v2 (6-8 digits). Idempotent; existing rows keep policy 1 (forces a PIN change at
-- next login), rows created from now on default to 2 because their PIN can only be set via 6-8 digit paths.
ALTER TABLE login_attempts
  ADD COLUMN IF NOT EXISTS factor text NOT NULL DEFAULT 'pin'
  CHECK (factor IN ('pin','otp','totp','unlock','recovery','step_up'));
CREATE INDEX IF NOT EXISTS idx_login_attempts_staff_failed
  ON login_attempts (staff_id, attempted_at DESC) WHERE succeeded = false;

ALTER TABLE staff ADD COLUMN IF NOT EXISTS mfa_last_used_step bigint;
ALTER TABLE staff ADD COLUMN IF NOT EXISTS pin_policy_version smallint NOT NULL DEFAULT 1;
ALTER TABLE staff ALTER COLUMN pin_policy_version SET DEFAULT 2;

ALTER TABLE master.operators ADD COLUMN IF NOT EXISTS mfa_last_used_step bigint;
ALTER TABLE master.operators ADD COLUMN IF NOT EXISTS pin_policy_version smallint NOT NULL DEFAULT 1;
ALTER TABLE master.operators ALTER COLUMN pin_policy_version SET DEFAULT 2;
