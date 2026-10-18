-- OPS-003 (merged PLT-017): the owner-level tables the tenant_id sweep of V211 cannot see.

-- legal_entities / tax_registrations hang off owner_id, have no code path in the API today and no tenant_id:
-- deny-all (FORCE RLS, no policy) and no grant for nabd_app. A story that needs them adds a reviewed policy + grant.
ALTER TABLE legal_entities ENABLE ROW LEVEL SECURITY;
ALTER TABLE legal_entities FORCE ROW LEVEL SECURITY;
ALTER TABLE tax_registrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_registrations FORCE ROW LEVEL SECURITY;

-- Owner PIN hashes: unreadable by the app role; sign-in goes through owner_auth_lookup (SECURITY DEFINER, owned by
-- nabd_definer). Exactly one key must be given. A column REVOKE cannot undo a table-level grant, hence the re-grant by column.
CREATE OR REPLACE FUNCTION owner_auth_lookup(p_email citext DEFAULT NULL, p_id uuid DEFAULT NULL, p_invite_token_hash text DEFAULT NULL)
  RETURNS TABLE (id uuid, name text, email citext, pin_hash text, status text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
  SELECT o.id, o.name, o.email, o.pin_hash, o.status
  FROM owners o
  WHERE (num_nonnulls(p_email, p_id, p_invite_token_hash) = 1)
    AND ((p_email IS NOT NULL AND o.email = p_email)
      OR (p_id IS NOT NULL AND o.id = p_id)
      OR (p_invite_token_hash IS NOT NULL AND o.invite_token_hash = p_invite_token_hash AND o.invite_expires_at > now()))
$$;
REVOKE ALL ON FUNCTION owner_auth_lookup(citext, uuid, text) FROM PUBLIC;

DO $$
DECLARE
  cols text;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    REVOKE ALL ON legal_entities, tax_registrations FROM nabd_app;
    SELECT string_agg(quote_ident(attname), ', ') INTO cols
      FROM pg_attribute WHERE attrelid = 'owners'::regclass AND attnum > 0 AND NOT attisdropped AND attname <> 'pin_hash';
    REVOKE SELECT ON owners FROM nabd_app;
    EXECUTE format('GRANT SELECT (%s) ON owners TO nabd_app', cols);
    GRANT EXECUTE ON FUNCTION owner_auth_lookup(citext, uuid, text) TO nabd_app;
  END IF;
  PERFORM nabd_adopt_definer_functions();
END $$;
