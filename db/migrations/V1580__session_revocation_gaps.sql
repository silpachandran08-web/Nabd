-- PLT-007: sessions end when they should (tenant suspension, role change, operator logout, 12-hour cap).
-- Existing rows get family_started_at = now() at deploy: live families get one fresh shift instead of an instant cut.
SET lock_timeout = '5s';

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS family_started_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE sessions DROP CONSTRAINT IF EXISTS sessions_revoked_reason_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_revoked_reason_check CHECK (revoked_reason IN
    ('logout', 'rotated', 'reuse_detected', 'suspended', 'admin_revoke', 'tenant_suspended', 'role_changed', 'max_age')) NOT VALID;
ALTER TABLE sessions VALIDATE CONSTRAINT sessions_revoked_reason_check;
-- bulk revoke on tenant suspension is one UPDATE over the tenant's live sessions
-- migration-lint: allow MIG-001 partial index on live sessions; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX IF NOT EXISTS idx_sessions_tenant_live ON sessions (tenant_id) WHERE revoked_at IS NULL;

ALTER TABLE master.sessions ADD COLUMN IF NOT EXISTS family_started_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE master.sessions DROP CONSTRAINT IF EXISTS sessions_revoked_reason_check;
ALTER TABLE master.sessions ADD CONSTRAINT sessions_revoked_reason_check CHECK (revoked_reason IN
    ('logout', 'rotated', 'reuse_detected', 'suspended', 'admin_revoke', 'max_age')) NOT VALID;
ALTER TABLE master.sessions VALIDATE CONSTRAINT sessions_revoked_reason_check;
