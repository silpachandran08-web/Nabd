-- PLT-005: owner accounts get TOTP MFA (enrol, challenge, recovery codes) before workspace selection.
-- Secrets stay unreadable by nabd_app: V212 granted SELECT on owners column by column, so the new secret columns are not
-- in it. The app reads them only through the SECURITY DEFINER functions below; mfa_enabled / mfa_last_used_step are not
-- secret and are granted so the replay-guard UPDATE can compare the step.
ALTER TABLE owners
  ADD COLUMN mfa_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN mfa_secret_enc bytea,
  ADD COLUMN mfa_pending_secret_enc bytea,
  ADD COLUMN mfa_last_used_step bigint;

-- No tenant_id: an owner is cross-tenant. Deny-all (FORCE RLS, no policy) and no app grant; definer functions only.
CREATE TABLE owner_mfa_recovery_codes (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id    uuid NOT NULL REFERENCES owners(id),
  code_hash   text NOT NULL,
  used_at     timestamptz,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_owner_mfa_recovery_codes_live ON owner_mfa_recovery_codes (owner_id) WHERE used_at IS NULL AND revoked_at IS NULL;
ALTER TABLE owner_mfa_recovery_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner_mfa_recovery_codes FORCE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION owner_mfa_state(p_owner uuid)
  RETURNS TABLE (mfa_enabled boolean, mfa_secret_enc bytea, mfa_pending_secret_enc bytea)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
  SELECT o.mfa_enabled, o.mfa_secret_enc, o.mfa_pending_secret_enc FROM owners o WHERE o.id = p_owner
$$;

-- Pending secret becomes active, earlier unused recovery codes are revoked and the new set (hashes) stored, atomically.
CREATE OR REPLACE FUNCTION owner_mfa_confirm(p_owner uuid, p_code_hashes text[]) RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  UPDATE owners SET mfa_secret_enc = mfa_pending_secret_enc, mfa_pending_secret_enc = NULL, mfa_enabled = true
   WHERE id = p_owner AND mfa_pending_secret_enc IS NOT NULL;
  IF NOT FOUND THEN
    RETURN false;
  END IF;
  UPDATE owner_mfa_recovery_codes SET revoked_at = now() WHERE owner_id = p_owner AND used_at IS NULL AND revoked_at IS NULL;
  INSERT INTO owner_mfa_recovery_codes (owner_id, code_hash) SELECT p_owner, h FROM unnest(p_code_hashes) h;
  RETURN true;
END $$;

-- Single use: NULL when the code is not a live one, else how many live codes remain.
CREATE OR REPLACE FUNCTION owner_mfa_consume_recovery(p_owner uuid, p_code_hash text) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  UPDATE owner_mfa_recovery_codes SET used_at = now()
   WHERE id = (SELECT id FROM owner_mfa_recovery_codes
                WHERE owner_id = p_owner AND code_hash = p_code_hash AND used_at IS NULL AND revoked_at IS NULL
                LIMIT 1 FOR UPDATE);
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;
  RETURN (SELECT count(*)::integer FROM owner_mfa_recovery_codes WHERE owner_id = p_owner AND used_at IS NULL AND revoked_at IS NULL);
END $$;

-- Platform fleet view: privileged identities of one clinic (built-in Owner role, mfa_required, or any approve /
-- refundDiscount grant; same rule as MfaPolicy) and how many have MFA on. Counts only. An owner's shadow staff row
-- counts as enrolled when the owner account is.
CREATE OR REPLACE FUNCTION tenant_privileged_mfa(p_tenant uuid) RETURNS TABLE (privileged integer, enrolled integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
  SELECT count(*)::integer,
         count(*) FILTER (WHERE CASE WHEN s.owner_id IS NOT NULL THEN COALESCE(o.mfa_enabled, false) ELSE s.mfa_enabled END)::integer
  FROM staff s
  JOIN roles r ON r.id = s.role_id
  LEFT JOIN owners o ON o.id = s.owner_id
  WHERE s.tenant_id = p_tenant AND s.status = 'active'
    AND (r.built_in OR r.mfa_required OR EXISTS (SELECT 1 FROM jsonb_array_elements(r.grants) g
                                                  WHERE g->>'approve' = 'true' OR g->>'refundDiscount' = 'true'))
$$;

REVOKE ALL ON FUNCTION owner_mfa_state(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION owner_mfa_confirm(uuid, text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION owner_mfa_consume_recovery(uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_privileged_mfa(uuid) FROM PUBLIC;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON owner_mfa_recovery_codes FROM nabd_app;
    GRANT SELECT (mfa_enabled, mfa_last_used_step) ON owners TO nabd_app;
    GRANT EXECUTE ON FUNCTION owner_mfa_state(uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION owner_mfa_confirm(uuid, text[]) TO nabd_app;
    GRANT EXECUTE ON FUNCTION owner_mfa_consume_recovery(uuid, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION tenant_privileged_mfa(uuid) TO nabd_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_reports') THEN
    REVOKE ALL ON owner_mfa_recovery_codes FROM nabd_reports;
  END IF;
END $$;

SELECT nabd_adopt_definer_functions();
