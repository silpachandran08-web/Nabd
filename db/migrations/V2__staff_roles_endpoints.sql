-- Supports the Staff/Roles endpoints (NB-039..048).

-- Field-level restriction grants ("no financial fields", "vitals & vaccines
-- only" — NB-042). A handful of string flags per staff member; a native
-- array is enough, no join table needed for this.
ALTER TABLE staff ADD COLUMN field_grants text[] NOT NULL DEFAULT '{}';

-- Same bootstrap problem as find_session_by_token_hash in V1: accepting an
-- invite starts with only an opaque token, no tenant known yet, but `staff`
-- is RLS-protected. Possessing the token is the authorization for this one
-- read, same trust model as any bearer credential.
CREATE FUNCTION find_staff_by_invite_token_hash(p_token_hash text)
RETURNS SETOF staff
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT * FROM staff WHERE invite_token_hash = p_token_hash;
$$;
REVOKE ALL ON FUNCTION find_staff_by_invite_token_hash(text) FROM PUBLIC;
-- deployment must additionally: GRANT EXECUTE ON FUNCTION find_staff_by_invite_token_hash(text) TO <app_role>;
