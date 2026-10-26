-- PLT-057 (MF-19, F-20, F-37, NB-266, SSA-04): support access starts only after the clinic owner approves it, and
-- support tickets store redacted text only. master schema, operator-plane tables (no RLS, see rls-exemptions.txt):
-- the owner-facing endpoints always filter on the caller's tenant_id.
SET lock_timeout = '5s';
-- migration-lint: allow MIG-001 master.support_access_grants holds a few hundred operator rows; CONCURRENTLY hangs the Flyway transaction

-- 1. Grant lifecycle. expires_at keeps one meaning for every state: the moment the grant stops being usable
--    (request deadline while 'requested', window end once 'active').
ALTER TABLE master.support_access_grants
  ADD COLUMN status text,
  ADD COLUMN minutes int,
  ADD COLUMN requested_at timestamptz,
  ADD COLUMN decided_by_staff_id uuid,
  ADD COLUMN decided_at timestamptz,
  ADD COLUMN decline_note text,
  ADD COLUMN window_started_at timestamptz,
  ADD COLUMN window_ends_at timestamptz;

-- Rows from before this change were self-granted by the operator. Any still-open window is closed now: from here
-- on a window exists only because an owner approved it.
UPDATE master.support_access_grants SET
  requested_at = granted_at,
  minutes = LEAST(60, GREATEST(1, ceil(extract(epoch FROM (expires_at - granted_at)) / 60)::int)),
  window_started_at = granted_at,
  window_ends_at = LEAST(expires_at, now()),
  revoked_at = CASE WHEN revoked_at IS NULL AND expires_at > now() THEN now() ELSE revoked_at END,
  status = CASE WHEN revoked_at IS NOT NULL THEN 'revoked'
                WHEN expires_at > now() THEN 'revoked' ELSE 'expired' END;

ALTER TABLE master.support_access_grants
  ADD CONSTRAINT support_access_grants_status_chk
    CHECK (status IS NOT NULL AND status IN ('requested','approved','declined','active','expired','revoked')) NOT VALID,
  ADD CONSTRAINT support_access_grants_minutes_chk CHECK (minutes IS NULL OR minutes BETWEEN 1 AND 60) NOT VALID,
  ADD CONSTRAINT support_access_grants_window_max_60_min
    CHECK (window_ends_at IS NULL OR window_started_at IS NULL OR window_ends_at - window_started_at <= interval '60 minutes') NOT VALID;
ALTER TABLE master.support_access_grants VALIDATE CONSTRAINT support_access_grants_status_chk;
ALTER TABLE master.support_access_grants VALIDATE CONSTRAINT support_access_grants_minutes_chk;
ALTER TABLE master.support_access_grants VALIDATE CONSTRAINT support_access_grants_window_max_60_min;

-- the 24 h request deadline is longer than the 60 min window the old check capped; the window check above replaces it
ALTER TABLE master.support_access_grants DROP CONSTRAINT support_access_grants_max_60_min;

-- one open request per operator and ticket: an owner is never asked twice for the same thing
CREATE UNIQUE INDEX uq_support_access_one_open_request
  ON master.support_access_grants (tenant_id, ticket_id, operator_id) WHERE status = 'requested';
CREATE INDEX idx_support_access_due ON master.support_access_grants (expires_at) WHERE status IN ('requested','active');

-- 2. Tickets keep the redacted text only.
ALTER TABLE master.support_tickets
  ADD COLUMN body_redacted boolean NOT NULL DEFAULT false,
  ADD COLUMN redaction_count int NOT NULL DEFAULT 0;
