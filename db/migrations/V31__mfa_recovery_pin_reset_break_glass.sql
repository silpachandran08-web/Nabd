-- E05 Identity, Auth & Session (NB-042/046/048).

-- NB-042: per-role policy switch, defaulting false so no existing role (in this app or any test
-- fixture) is retroactively gated. A role's grants or its display name are not a safe signal for
-- this — free-form role names and the "full access" convenience roles used throughout tests both
-- collide with any name/grant-based heuristic. An explicit flag, set deliberately per role via the
-- role builder, is the only version of "enforced by policy" that doesn't also silently lock people
-- out of roles never intended to require it.
ALTER TABLE roles ADD COLUMN mfa_required boolean NOT NULL DEFAULT false;

-- NB-042: single-use recovery codes, generated once at MFA enrollment confirmation.
CREATE TABLE mfa_recovery_codes (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  staff_id    uuid NOT NULL REFERENCES staff(id),
  code_hash   text NOT NULL,
  used_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE mfa_recovery_codes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mfa_recovery_codes
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_mfa_recovery_codes_staff ON mfa_recovery_codes (staff_id) WHERE used_at IS NULL;

-- NB-046: PIN reset — same hash/expiry shape as the WhatsApp login OTP columns, kept separate so a
-- pending reset can never be confused with (or consumed as) a pending login OTP.
ALTER TABLE staff
  ADD COLUMN pin_reset_token_hash text,
  ADD COLUMN pin_reset_expires_at timestamptz;

-- NB-048: break-glass emergency access. Time-boxed and self-activated with a mandatory reason;
-- enforcement folds the tenant's built-in Owner role's grants into the activator's token for the
-- window (see AuthService.mintTokenPair) rather than inventing a second permission concept.
CREATE TABLE break_glass_grants (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           uuid NOT NULL REFERENCES tenants(id),
  staff_id            uuid NOT NULL REFERENCES staff(id),
  reason              text NOT NULL,
  activated_at        timestamptz NOT NULL DEFAULT now(),
  expires_at          timestamptz NOT NULL,
  deactivated_at      timestamptz,
  deactivated_reason  text CHECK (deactivated_reason IN ('expired','manual'))
);
ALTER TABLE break_glass_grants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON break_glass_grants
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_break_glass_active ON break_glass_grants (tenant_id) WHERE deactivated_at IS NULL;
