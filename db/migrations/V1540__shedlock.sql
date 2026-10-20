-- OPS-013: ShedLock lock table (JdbcTemplateLockProvider, usingDbTime). One row per job name; the replica that wins the
-- conditional UPDATE/INSERT runs the job, every other replica skips it. Platform bookkeeping: no tenant_id, no tenant
-- data, so no RLS. lock_until/locked_at are plain timestamp on purpose: ShedLock's Postgres DB-time SQL writes and
-- compares timezone('utc', now()), which is only session-time-zone independent in a timestamp (without tz) column.
SET lock_timeout = '5s';
CREATE TABLE IF NOT EXISTS shedlock (
  name       varchar(64)  NOT NULL PRIMARY KEY,
  lock_until timestamp    NOT NULL,
  locked_at  timestamp    NOT NULL,
  locked_by  varchar(255) NOT NULL
);

-- The API (nabd_app) runs the jobs: ShedLock only SELECTs, INSERTs and UPDATEs (it never deletes). Default privileges
-- (V210) would also give DELETE to nabd_app and SELECT to nabd_reports; neither needs them.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON shedlock FROM nabd_app;
    GRANT SELECT, INSERT, UPDATE ON shedlock TO nabd_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_reports') THEN
    REVOKE ALL ON shedlock FROM nabd_reports;
  END IF;
END $$;
