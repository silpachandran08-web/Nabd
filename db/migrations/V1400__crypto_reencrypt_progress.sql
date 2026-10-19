-- PLT-044: checkpoint of the resumable field re-encrypt job (CryptoReencryptJob), one row per encrypted column.
-- Platform-only bookkeeping: no tenant_id and no tenant data (last_id is "<tenant uuid>|<row key>", target_kid the kid
-- the pass rewrites to; a finished row whose target_kid is no longer active restarts, which is how rotation re-runs).
SET lock_timeout = '3s';
CREATE TABLE crypto_reencrypt_progress (
  table_name  text        NOT NULL,
  column_name text        NOT NULL,
  last_id     text,
  rows_done   bigint      NOT NULL DEFAULT 0,
  target_kid  int         NOT NULL,
  started_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  PRIMARY KEY (table_name, column_name)
);
