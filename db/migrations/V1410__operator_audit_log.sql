-- PLT-056 (MF-19, SEC-14, SEC-20, SEC-37): operator access to the cross-tenant audit log is itself accountable,
-- and support grants are narrowed to one tenant, one ticket and one scope for at most 60 minutes.

SET lock_timeout = '5s';

-- 1. Operator-plane ledger of every audit-console search and export. master schema, no tenant_id column (the
--    target tenant is target_tenant_id), so it is operator-plane data like master.support_tickets. params holds
--    filter values only (never PHI: the actor-name search text is recorded as present/absent).
CREATE TABLE master.operator_audit_log (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  operator_id       uuid NOT NULL REFERENCES master.operators(id),
  action            text NOT NULL,
  target_tenant_id  uuid,  -- no FK: the ledger keeps whatever tenant id was searched, known or not
  params            jsonb NOT NULL DEFAULT '{}'::jsonb,
  row_count         int NOT NULL CHECK (row_count >= 0),
  reason            text,
  ip                inet,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_operator_audit_log_operator ON master.operator_audit_log (operator_id, created_at);

CREATE FUNCTION master.operator_audit_log_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'master.operator_audit_log is append-only: % is not permitted', TG_OP;
END $$;
CREATE TRIGGER trg_operator_audit_log_no_update BEFORE UPDATE OR DELETE ON master.operator_audit_log
  FOR EACH ROW EXECUTE FUNCTION master.operator_audit_log_immutable();
CREATE TRIGGER trg_operator_audit_log_no_truncate BEFORE TRUNCATE ON master.operator_audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION master.operator_audit_log_immutable();

-- 2. Support grants: a ticket and a scope; the window is capped at 60 minutes. Rows from before this change have
--    no ticket and were effectively the redacted patient view, so they are backfilled to that scope; the duration
--    check is NOT VALID because legacy rows computed expires_at on the app clock (could exceed 60 min by ms).
ALTER TABLE master.support_access_grants
  ADD COLUMN ticket_id uuid REFERENCES master.support_tickets(id),
  ADD COLUMN scope text;
UPDATE master.support_access_grants SET scope = 'redacted_patient_view' WHERE scope IS NULL;
-- NOT NULL is part of the scope CHECK (validated below), so no SET NOT NULL scan is needed.
ALTER TABLE master.support_access_grants
  ADD CONSTRAINT support_access_grants_scope_chk
    CHECK (scope IS NOT NULL AND scope IN ('config_only', 'redacted_patient_view')) NOT VALID,
  ADD CONSTRAINT support_access_grants_max_60_min CHECK (expires_at - granted_at <= interval '60 minutes') NOT VALID;
ALTER TABLE master.support_access_grants VALIDATE CONSTRAINT support_access_grants_scope_chk;

-- 3. search_audit_log (both overloads) leaves the API role: only the platform role nabd_platform
--    (infra/db/bootstrap-roles.sql, the PLT-009 interface) may execute it.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE UPDATE, DELETE ON master.operator_audit_log FROM nabd_app;
    REVOKE EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,bigint,int) FROM nabd_app;
    REVOKE EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int) FROM nabd_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_platform') THEN
    GRANT EXECUTE ON FUNCTION search_audit_log(uuid,text,text,timestamptz,timestamptz,uuid,text,text,text,bigint,int) TO nabd_platform;
  END IF;
END $$;
