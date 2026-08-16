-- Foundation schema backing the login flow: tenants/roles stubs + staff,
-- sessions (refresh-token chain) and login_attempts (lockout backoff).
-- Refs: NB-002 (RLS tenancy), NB-003 (core model), NB-029 (lockout),
-- NB-032 (session/device trust), NB-039 (RBAC).
--
-- The app DB role must be a non-owner grantee of these tables — RLS is
-- bypassed by the table owner and superusers by default.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── tenants (minimal stub; full E06 schema lands with the tenancy epic) ──
CREATE TABLE tenants (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug        citext NOT NULL UNIQUE,  -- subdomain / clinic code; resolves which tenant a login belongs to
  name        text NOT NULL,
  region      text NOT NULL CHECK (region IN ('IN', 'KSA')),
  status      text NOT NULL DEFAULT 'trial' CHECK (status IN ('trial','active','suspended','offboarded')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── roles (minimal stub; custom-role builder UI lands with the E05 RBAC epic) ──
CREATE TABLE roles (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  name        text NOT NULL,
  built_in    boolean NOT NULL DEFAULT false,
  grants      jsonb NOT NULL DEFAULT '[]',  -- ModuleGrant[] shape from api/openapi.yaml
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ── staff ──
CREATE TABLE staff (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  role_id            uuid NOT NULL REFERENCES roles(id),
  email              citext NOT NULL,
  name               text NOT NULL,
  password_hash      text,                    -- PHC-format Argon2id hash; null while status='invited'
  status             text NOT NULL DEFAULT 'invited' CHECK (status IN ('invited','active','suspended')),
  scope              text NOT NULL DEFAULT 'all_clinic_patients' CHECK (scope IN ('own_patients_only','all_clinic_patients')),
  mfa_enabled        boolean NOT NULL DEFAULT false,
  mfa_secret_enc     bytea,                   -- KMS/app-encrypted TOTP secret; never stored plaintext
  invite_token_hash  text,                    -- sha256(invite token); raw token exists only in the invite email
  invite_expires_at  timestamptz,
  invited_by         uuid REFERENCES staff(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, email)
);
CREATE TRIGGER trg_staff_updated_at BEFORE UPDATE ON staff
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE staff ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- login step 2: resolve staff by (tenant, email)
CREATE INDEX idx_staff_tenant_email ON staff (tenant_id, email);
-- invite-accept lookup by hashed token
CREATE UNIQUE INDEX idx_staff_invite_token_hash ON staff (invite_token_hash) WHERE invite_token_hash IS NOT NULL;

-- ── sessions: one row per refresh-token "generation" in a rotation chain ──
CREATE TABLE sessions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  staff_id        uuid NOT NULL REFERENCES staff(id),
  family_id       uuid NOT NULL,              -- constant across a chain; whole family revoked together on reuse
  token_hash      text NOT NULL,              -- sha256(refresh_token); raw token never stored
  device_label    text,
  ip_address      inet,
  user_agent      text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  last_seen_at    timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  revoked_at      timestamptz,
  revoked_reason  text CHECK (revoked_reason IN ('logout','rotated','reuse_detected','suspended','admin_revoke')),
  UNIQUE (token_hash),
  CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

ALTER TABLE sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sessions
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- refresh-token verification: look up the one active row for a presented hash
CREATE INDEX idx_sessions_token_hash ON sessions (token_hash) WHERE revoked_at IS NULL;
-- reuse detection: revoke the whole family in one statement
CREATE INDEX idx_sessions_family ON sessions (family_id) WHERE revoked_at IS NULL;
-- GET /auth/sessions device list, and staff-suspend session kill
CREATE INDEX idx_sessions_staff_active ON sessions (staff_id) WHERE revoked_at IS NULL;

-- ponytail: no purge job yet for expired/revoked rows — add a nightly
-- DELETE WHERE expires_at < now() - interval '30 days' once row count matters.

-- Refresh-token rotation has a bootstrap problem: RLS on `sessions` requires
-- app.tenant_id to already be set, but the whole point of this lookup is to
-- *discover* which tenant a presented refresh token belongs to. Possessing
-- the 256-bit token is itself the authorization for this one read (same
-- trust model as any bearer credential), so it runs as SECURITY DEFINER to
-- step outside RLS for exactly this query — every other session query still
-- goes through the app role and is fully RLS-scoped.
CREATE FUNCTION find_session_by_token_hash(p_token_hash text)
RETURNS SETOF sessions
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM sessions WHERE token_hash = p_token_hash;
$$;
REVOKE ALL ON FUNCTION find_session_by_token_hash(text) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION find_session_by_token_hash(text) TO <app_role>;

-- ── login_attempts: lockout backoff + brute-force telemetry (NB-029) ──
CREATE TABLE login_attempts (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id     uuid REFERENCES tenants(id),   -- null if the tenant/email couldn't be resolved
  staff_id      uuid REFERENCES staff(id),     -- null until the account is resolved
  email         citext NOT NULL,               -- as submitted; tracked pre-resolution too, to rate-limit probing
  ip_address    inet NOT NULL,
  succeeded     boolean NOT NULL,
  attempted_at  timestamptz NOT NULL DEFAULT now()
);
-- Append-only telemetry, always queried pre-scoped by staff_id/email/ip — not
-- tenant-listed directly, so no RLS policy here (deliberately excluded from
-- the default tenant-scoped repository to avoid an accidental cross-tenant read).

-- the three lookups the login flow actually runs:
CREATE INDEX idx_login_attempts_staff_time ON login_attempts (staff_id, attempted_at DESC) WHERE staff_id IS NOT NULL;
CREATE INDEX idx_login_attempts_email_time ON login_attempts (email, attempted_at DESC);
CREATE INDEX idx_login_attempts_ip_time ON login_attempts (ip_address, attempted_at DESC);

-- ponytail: unbounded table, add a nightly DELETE WHERE attempted_at < now()
-- - interval '30 days' once volume matters (lockout logic only ever looks
-- back a few minutes).
