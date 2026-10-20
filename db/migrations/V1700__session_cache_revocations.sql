SET lock_timeout = '5s';
-- OPS-020 (FIX-C, QA-7 D4): revocations must fail closed on every replica within 1 s even when the revoking pod cannot
-- reach Valkey (its "0" marker never lands and the other pods keep a live "1" for up to 60 s). Every revocation also
-- writes its session ids here in the revoking transaction; every pod re-reads the last minute of rows at least every
-- 750 ms (SessionLivenessCache) and stops trusting a live marker for those ids. Ids and times only: no tenant data,
-- so no tenant_id and no RLS (like sessions.id it is operational). Rows older than 10 minutes are deleted on insert.
CREATE TABLE session_cache_revocations (
  session_id uuid PRIMARY KEY,
  revoked_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_cache_revocations_at ON session_cache_revocations (revoked_at);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON session_cache_revocations TO nabd_app;
  END IF;
END $$;
