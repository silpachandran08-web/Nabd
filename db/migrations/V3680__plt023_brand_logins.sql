-- PLT-023: one login for a person who works in several branches of one brand.
-- 1. brand_logins: the brand-level identity that links that person's staff rows (one per branch). No tenant_id: it sits above
--    the clinics, so the app role gets no privilege on it and reaches it only through the SECURITY DEFINER functions below.
--    Designed to map 1:1 onto identity.practitioner later (T-246).
-- 2. staff.brand_login_id: the link. Only the definer functions may set it (trigger): the link is what lets one branch's
--    sign-in open another branch, so no tenant-scoped statement of the app can create one.
-- 3. brand_login_token_uses: one-time ids (jti) of branch-select tokens and of the step-up that approved a link.
-- 4. sessions.reports_only: an 'All branches (reports)' session stays reports-only across refresh.
-- Additive only. No token, policy or GUC is widened: every function returns ids and names of the caller's own brand.
SET lock_timeout = '5s';

CREATE TABLE brand_logins (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  brand_id    uuid NOT NULL REFERENCES brands(id),
  phone_hmac  bytea,
  email_lower text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_brand_logins_brand ON brand_logins (brand_id);

CREATE TABLE brand_login_token_uses (
  jti     uuid PRIMARY KEY,
  used_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE staff ADD COLUMN IF NOT EXISTS brand_login_id uuid REFERENCES brand_logins(id);
-- migration-lint: allow MIG-001 partial index over the few linked rows; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX IF NOT EXISTS idx_staff_brand_login ON staff (brand_login_id) WHERE brand_login_id IS NOT NULL;

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS reports_only boolean NOT NULL DEFAULT false;

DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['nabd_app', 'nabd_reports', 'nabd_platform', 'nabd_control'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON brand_logins, brand_login_token_uses FROM %I', r);
    END IF;
  END LOOP;
END $$;

-- The link column is written by link_brand_staff() only (it runs as the definer role, never as an application role).
CREATE FUNCTION staff_brand_login_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF current_user IN ('nabd_app', 'nabd_reports', 'nabd_platform', 'nabd_control')
     AND (TG_OP = 'INSERT' AND NEW.brand_login_id IS NOT NULL
          OR TG_OP = 'UPDATE' AND NEW.brand_login_id IS DISTINCT FROM OLD.brand_login_id) THEN
    RAISE EXCEPTION 'staff.brand_login_id is set only by link_brand_staff()' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_staff_brand_login_guard BEFORE INSERT OR UPDATE OF brand_login_id ON staff
  FOR EACH ROW EXECUTE FUNCTION staff_brand_login_guard();

-- "Same mobile number" for linking: the last nine digits, however the number was typed; NULL for no usable number.
CREATE FUNCTION staff_phone_key(p_phone text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN length(regexp_replace(p_phone, '\D', '', 'g')) >= 7 THEN right(regexp_replace(p_phone, '\D', '', 'g'), 9) END
$$;

-- The branches the person behind one staff row may enter: the staff rows linked to the same brand login, in clinics of that
-- login's brand only, or (the brand's owner account) every clinic of the brand they are signed in to. Ids, names and statuses
-- only. The caller passes its own staff row and tenant, both taken from a verified token.
CREATE FUNCTION brand_login_branches(p_staff_id uuid, p_tenant_id uuid)
RETURNS TABLE (o_tenant_id uuid, o_staff_id uuid, o_name text, o_branch_code text, o_tenant_status text, o_staff_status text,
               o_brand_login_id uuid, o_owner_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT t.id, s.id, t.name, t.branch_code, t.status, s.status, bl.id, NULL::uuid
  FROM staff me
  JOIN brand_logins bl ON bl.id = me.brand_login_id
  JOIN tenants mt ON mt.id = me.tenant_id AND mt.brand_id = bl.brand_id
  JOIN staff s ON s.brand_login_id = bl.id
  JOIN tenants t ON t.id = s.tenant_id AND t.brand_id = bl.brand_id
  WHERE me.id = p_staff_id AND me.tenant_id = p_tenant_id
  UNION ALL
  SELECT t.id, s.id, t.name, t.branch_code, t.status, COALESCE(s.status, 'active'), NULL::uuid, o.id
  FROM staff me
  JOIN tenants mt ON mt.id = me.tenant_id
  JOIN brands b ON b.id = mt.brand_id AND b.owner_id = me.owner_id
  JOIN owners o ON o.id = b.owner_id AND o.status = 'active'
  JOIN tenants t ON t.brand_id = b.id
  LEFT JOIN staff s ON s.tenant_id = t.id AND s.owner_id = o.id
  WHERE me.id = p_staff_id AND me.tenant_id = p_tenant_id AND me.owner_id IS NOT NULL AND me.brand_login_id IS NULL
  ORDER BY 3, 1
$$;

-- True exactly once per id. Tokens live five minutes, so day-old rows are dropped on the way.
CREATE FUNCTION consume_brand_login_token(p_jti uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE n int;
BEGIN
  DELETE FROM brand_login_token_uses u WHERE u.used_at < now() - interval '1 day';
  INSERT INTO brand_login_token_uses (jti) VALUES (p_jti) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n = 1;
END $$;

-- Links one staff row per branch to one brand login. The caller must be the owner account of the brand (built-in Owner role
-- in the clinic they are signed in to), which makes them the owner of every branch named; every staff row must be an active,
-- ordinary staff row of a different clinic of that same brand, and they must be the same person (same email, or same mobile
-- number). p_step_up is the one-time id of the owner's confirmation. One audit row is written in every branch named.
CREATE FUNCTION link_brand_staff(p_owner_staff_id uuid, p_owner_tenant_id uuid, p_staff_ids uuid[], p_step_up uuid, p_ip text)
RETURNS TABLE (o_status text, o_brand_login_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_owner_name text; v_brand uuid; v_login uuid; v_email text;
  v_found int; v_in_brand int; v_tenants int; v_eligible int; v_emails int; v_phones int; v_with_phone int; v_logins int; v_linked int;
  v_used int;
BEGIN
  SELECT s.name, b.id INTO v_owner_name, v_brand
  FROM staff s
  JOIN roles r ON r.id = s.role_id AND r.tenant_id = s.tenant_id AND r.built_in
  JOIN tenants t ON t.id = s.tenant_id
  JOIN brands b ON b.id = t.brand_id
  JOIN owners o ON o.id = b.owner_id AND o.status = 'active'
  WHERE s.id = p_owner_staff_id AND s.tenant_id = p_owner_tenant_id AND s.status = 'active'
    AND (s.owner_id = o.id OR (s.owner_id IS NULL AND s.email = o.email));
  IF v_brand IS NULL THEN
    RETURN QUERY SELECT 'NOT_OWNER'::text, NULL::uuid; RETURN;
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(v_brand::text, 23));
  PERFORM 1 FROM staff s WHERE s.id = ANY(p_staff_ids) FOR UPDATE;

  SELECT count(*), count(*) FILTER (WHERE t.brand_id = v_brand), count(DISTINCT s.tenant_id),
         count(*) FILTER (WHERE s.status = 'active' AND s.owner_id IS NULL),
         count(DISTINCT lower(s.email::text)), min(lower(s.email::text)),
         count(DISTINCT staff_phone_key(s.mobile_phone)), count(staff_phone_key(s.mobile_phone)), count(DISTINCT s.brand_login_id), count(s.brand_login_id), min(s.brand_login_id::text)::uuid
    INTO v_found, v_in_brand, v_tenants, v_eligible, v_emails, v_email, v_phones, v_with_phone, v_logins, v_linked, v_login
  FROM staff s JOIN tenants t ON t.id = s.tenant_id
  WHERE s.id = ANY(p_staff_ids);

  IF cardinality(p_staff_ids) < 2 OR v_found <> cardinality(p_staff_ids) OR v_in_brand <> v_found THEN
    RETURN QUERY SELECT 'NOT_FOUND'::text, NULL::uuid; RETURN; -- unknown, or a clinic of another brand: the same answer
  ELSIF v_tenants <> v_found THEN
    RETURN QUERY SELECT 'ONE_PER_BRANCH'::text, NULL::uuid; RETURN;
  ELSIF v_eligible <> v_found THEN
    RETURN QUERY SELECT 'NOT_ELIGIBLE'::text, NULL::uuid; RETURN;
  ELSIF NOT (v_emails = 1 OR (v_with_phone = v_found AND v_phones = 1)) THEN
    RETURN QUERY SELECT 'IDENTITY_MISMATCH'::text, NULL::uuid; RETURN;
  ELSIF v_logins > 1 OR v_linked = v_found
        OR (v_login IS NOT NULL AND NOT EXISTS (SELECT 1 FROM brand_logins bl WHERE bl.id = v_login AND bl.brand_id = v_brand)) THEN
    RETURN QUERY SELECT 'ALREADY_LINKED'::text, NULL::uuid; RETURN;
  END IF;

  INSERT INTO brand_login_token_uses (jti) VALUES (p_step_up) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS v_used = ROW_COUNT;
  IF v_used <> 1 THEN
    RETURN QUERY SELECT 'STEP_UP_REUSED'::text, NULL::uuid; RETURN;
  END IF;

  IF v_login IS NULL THEN
    INSERT INTO brand_logins (brand_id, email_lower) VALUES (v_brand, CASE WHEN v_emails = 1 THEN v_email END)
    RETURNING id INTO v_login;
  END IF;
  UPDATE staff s SET brand_login_id = v_login WHERE s.id = ANY(p_staff_ids) AND s.brand_login_id IS NULL;

  INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id, after)
  SELECT s.tenant_id, 'staff', p_owner_staff_id, v_owner_name, 'Owner', p_ip::inet, 'staff.branch_linked', 'staff', s.id,
         jsonb_build_object('brandLoginId', v_login, 'approvedInTenantId', p_owner_tenant_id,
                            'linkedTenantIds', (SELECT jsonb_agg(x.tenant_id ORDER BY x.tenant_id) FROM staff x WHERE x.id = ANY(p_staff_ids)))
  FROM staff s WHERE s.id = ANY(p_staff_ids) ORDER BY s.tenant_id;

  RETURN QUERY SELECT 'LINKED'::text, v_login;
END $$;

-- For the owner's "link across branches" form: the staff rows in the brand's other branches that are the same person as one
-- staff member of the branch the owner is signed in to (same email, or same mobile number). Nothing for anyone but the owner
-- account of that brand. Branch name, staff name and role name only.
CREATE FUNCTION brand_staff_link_candidates(p_owner_staff_id uuid, p_owner_tenant_id uuid, p_staff_id uuid)
RETURNS TABLE (o_tenant_id uuid, o_branch_name text, o_staff_id uuid, o_staff_name text, o_role_name text, o_linked boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT t.id, t.name, c.id, c.name, cr.name, c.brand_login_id IS NOT NULL
  FROM staff os
  JOIN roles r ON r.id = os.role_id AND r.tenant_id = os.tenant_id AND r.built_in
  JOIN tenants ot ON ot.id = os.tenant_id
  JOIN brands b ON b.id = ot.brand_id
  JOIN owners o ON o.id = b.owner_id AND o.status = 'active' AND (os.owner_id = o.id OR (os.owner_id IS NULL AND os.email = o.email))
  JOIN staff me ON me.id = p_staff_id AND me.tenant_id = os.tenant_id AND me.status = 'active' AND me.owner_id IS NULL
  JOIN tenants t ON t.brand_id = b.id AND t.id <> os.tenant_id
  JOIN staff c ON c.tenant_id = t.id AND c.status = 'active' AND c.owner_id IS NULL
    AND (lower(c.email::text) = lower(me.email::text)
         OR staff_phone_key(c.mobile_phone) = staff_phone_key(me.mobile_phone))
  JOIN roles cr ON cr.id = c.role_id
  WHERE os.id = p_owner_staff_id AND os.tenant_id = p_owner_tenant_id AND os.status = 'active'
  ORDER BY t.name, c.name
$$;

REVOKE ALL ON FUNCTION brand_login_branches(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION brand_staff_link_candidates(uuid, uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION consume_brand_login_token(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION link_brand_staff(uuid, uuid, uuid[], uuid, text) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION brand_login_branches(uuid, uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION brand_staff_link_candidates(uuid, uuid, uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION consume_brand_login_token(uuid) TO nabd_app;
    GRANT EXECUTE ON FUNCTION link_brand_staff(uuid, uuid, uuid[], uuid, text) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
