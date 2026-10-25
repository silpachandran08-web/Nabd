SET lock_timeout = '5s';
-- COM-007: Comms DB baseline. Roles are cluster-wide; passwords are set by the bootstrap (secret store), never here.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'comms_migrator') THEN
    CREATE ROLE comms_migrator LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'comms_app') THEN
    CREATE ROLE comms_app LOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;
ALTER ROLE comms_app SET statement_timeout = '5s';
ALTER ROLE comms_app SET lock_timeout = '3s';
ALTER ROLE comms_app SET idle_in_transaction_session_timeout = '15s';

-- Consumer idempotency ledger: one row per CloudEvents id applied (infrastructure, no PHI).
CREATE TABLE processed_events (
  consumer   text        NOT NULL,
  event_id   text        NOT NULL,
  tenant_id  uuid        NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE shedlock (
  name       varchar(64)  NOT NULL PRIMARY KEY,
  lock_until timestamptz  NOT NULL,
  locked_at  timestamptz  NOT NULL,
  locked_by  varchar(255) NOT NULL
);
