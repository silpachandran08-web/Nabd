SET lock_timeout = '5s';
-- OPS-067 (DP-05, SEC-27): monthly signed checkpoint of every tenant's audit chain head. Written once per (tenant, month)
-- by the seal job (AuditChainSealer); append-only for the app role. The signature is an HMAC today; the off-box copy
-- in the regional archive bucket is infrastructure (see docs/runbooks/audit-chain-seals.md).
CREATE TABLE audit_chain_seals (
  tenant_id uuid NOT NULL,
  month     date NOT NULL CHECK (month = date_trunc('month', month)::date),
  last_id   bigint NOT NULL,
  last_hash text NOT NULL,
  sealed_at timestamptz NOT NULL DEFAULT now(),
  signature text NOT NULL,
  PRIMARY KEY (tenant_id, month)
);
SELECT enforce_tenant_rls('audit_chain_seals');
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN REVOKE UPDATE, DELETE ON audit_chain_seals FROM nabd_app; END IF;
END $$;
