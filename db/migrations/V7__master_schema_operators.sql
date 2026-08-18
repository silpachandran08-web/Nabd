-- DRAFT — not yet in db/migrations/, not applied anywhere. For review only.
--
-- Platform operators (Nabd's own team: Super Admin and the other 6 SaaS
-- roles) live in a dedicated `master` schema, fully separate from `public`
-- where every tenant/owner/clinic table lives. This is a deliberate
-- architecture choice, not a reserved-tenant reuse hack: operators are not
-- customers, not tenant-scoped, and don't share RLS machinery with
-- clinic data at all.

CREATE SCHEMA master;

-- ── master.operators ──
-- Seven fixed roles (SaaS Operator Roles sheet: "No single super-admin
-- does everything"), not a flexible custom-role system like clinic RBAC —
-- there's no builder for these, the set is closed.
CREATE TABLE master.operators (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name            text NOT NULL,
  email           citext NOT NULL UNIQUE,
  pin_hash        text,                    -- null until activated, same Argon2id convention as staff/owners
  role            text NOT NULL CHECK (role IN (
                    'super_admin', 'implementation', 'support_engineer',
                    'billing', 'sre', 'commercial', 'compliance_dpo'
                  )),
  status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
  mfa_enabled     boolean NOT NULL DEFAULT false,
  mfa_secret_enc  bytea,                   -- KMS/app-encrypted TOTP secret, same as staff.mfa_secret_enc
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_operators_updated_at BEFORE UPDATE ON master.operators
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ── master.sessions ──
-- Same family-based rotation + reuse-detection shape as public.sessions.
-- No RLS: unlike staff sessions (which need app.tenant_id to scope a
-- lookup before the caller's tenant is known), there is no tenant concept
-- here to bootstrap around — operators aren't scoped to anything, so a
-- plain token_hash lookup is the whole story, no SECURITY DEFINER
-- function needed either.
CREATE TABLE master.sessions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id     uuid NOT NULL REFERENCES master.operators(id),
  family_id       uuid NOT NULL,
  token_hash      text NOT NULL,
  device_label    text,
  ip_address      inet,
  user_agent      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  last_seen_at    timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  revoked_at      timestamptz,
  revoked_reason  text CHECK (revoked_reason IN ('logout', 'rotated', 'reuse_detected', 'suspended', 'admin_revoke')),
  UNIQUE (token_hash),
  CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX idx_master_sessions_operator ON master.sessions (operator_id) WHERE revoked_at IS NULL;

-- ── master.login_attempts ──
-- Deliberately its own table, not a 4th nullable actor column bolted onto
-- public.login_attempts — keeps operator data fully out of the public
-- schema, per the instruction this migration implements.
CREATE TABLE master.login_attempts (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  operator_id   uuid REFERENCES master.operators(id),
  email         citext NOT NULL,
  ip_address    inet NOT NULL,
  succeeded     boolean NOT NULL,
  attempted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_master_login_attempts_operator_time
  ON master.login_attempts (operator_id, attempted_at DESC) WHERE operator_id IS NOT NULL;
