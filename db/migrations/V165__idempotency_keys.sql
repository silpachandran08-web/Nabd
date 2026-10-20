-- PLT-026 (T-031): Idempotency-Key store. One row per (tenant, actor, key); response_body_enc is AES-GCM ciphertext
SET lock_timeout = '5s';
-- (the body may hold PHI) and the row is purged after 24 h (see purge_idempotency_keys, V166).
CREATE TABLE idempotency_keys (
  tenant_id         uuid        NOT NULL,
  actor_id          uuid        NOT NULL,
  idem_key          uuid        NOT NULL,
  method            text        NOT NULL,
  path              text        NOT NULL,
  request_hash      bytea       NOT NULL,
  status            text        NOT NULL CHECK (status IN ('in_progress', 'completed')),
  response_status   int,
  response_body_enc bytea,
  created_at        timestamptz NOT NULL DEFAULT now(),
  expires_at        timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, actor_id, idem_key)
);
CREATE INDEX idx_idempotency_keys_expires ON idempotency_keys (expires_at);

-- V165 sorts before V210 (which defines enforce_tenant_rls and re-applies the same policies to every tenant_id table),
-- so the policies are written out here; the DO block only re-applies the helper where it already exists.
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY t_select ON idempotency_keys FOR SELECT
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);  -- own tenant only: replay bodies may hold PHI, never widen (PLT-018)
CREATE POLICY t_insert ON idempotency_keys FOR INSERT WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY t_update ON idempotency_keys FOR UPDATE
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY t_delete ON idempotency_keys FOR DELETE USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
DO $$ BEGIN
  IF to_regproc('enforce_tenant_rls(regclass,text)') IS NOT NULL THEN PERFORM enforce_tenant_rls('idempotency_keys'); END IF;
END $$;
