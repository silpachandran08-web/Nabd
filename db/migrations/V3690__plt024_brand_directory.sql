-- PLT-024 (SYS-24, CONS M2, ADR-08): a patient registered at one branch is recognised at a sibling branch through an explicit,
-- logged link. RLS stays single-tenant (PLT-021): everything that crosses a clinic boundary is one of the SECURITY DEFINER
-- functions below. Each derives the caller's clinic from app_tenant_id() (bound from the token at transaction start, never a
-- parameter), reaches only clinics of the caller's own brand AND region, and only while brands.chain_sharing_profile is
-- DEMOGRAPHICS (ending a link is the exception: it must work after sharing is switched off).
-- Before a link the desk gets name, sex and year of birth only. Never listed: a patient who opted out, withdrew
-- data-processing consent, is a demo record, is merged, has an invalid number, or is a minor / has a guardian (a guardian cannot
-- be copied to another clinic, so a minor is registered in person).
SET lock_timeout = '5s';

ALTER TABLE brands ADD COLUMN chain_sharing_profile text NOT NULL DEFAULT 'NONE';
ALTER TABLE brands ADD CONSTRAINT brands_chain_sharing_profile_check CHECK (chain_sharing_profile IN ('NONE', 'DEMOGRAPHICS')) NOT VALID;
ALTER TABLE brands VALIDATE CONSTRAINT brands_chain_sharing_profile_check;

ALTER TABLE patients ADD COLUMN brand_directory_opt_out boolean NOT NULL DEFAULT false;

-- One row per side of a link. linked_patient_ref is the pairwise reference of the other clinic's patient (brand_pair_ref), never
-- its id. linked_by is the staff member of THIS clinic who linked (null on the side that was found).
CREATE TABLE patient_branch_links (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  patient_id         uuid NOT NULL,
  linked_tenant_id   uuid NOT NULL REFERENCES tenants(id),
  linked_patient_ref text NOT NULL,
  linked_by          uuid,
  basis              text NOT NULL CHECK (basis IN ('desk_phone_dob')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT patient_branch_links_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT patient_branch_links_once UNIQUE (tenant_id, patient_id, linked_tenant_id),
  CONSTRAINT patient_branch_links_peer UNIQUE (tenant_id, linked_tenant_id, linked_patient_ref),
  CHECK (linked_tenant_id <> tenant_id)
);
SELECT enforce_tenant_rls('patient_branch_links');

-- phi_read_log gains the purpose 'brand_directory_lookup'. The old check is found by definition, not by name, so this also
-- works if the table was rebuilt by another migration of this release.
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'phi_read_log'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%purpose_code%' LOOP
    EXECUTE format('ALTER TABLE phi_read_log DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE phi_read_log ADD CONSTRAINT phi_read_log_purpose_code_check
  CHECK (purpose_code IN ('treatment', 'billing', 'support', 'break_glass', 'export', 'directory', 'brand_directory_lookup')) NOT VALID;
ALTER TABLE phi_read_log VALIDATE CONSTRAINT phi_read_log_purpose_code_check;

-- The reference of clinic p_home's patient as clinic p_viewer sees it. Pairwise: another viewer gets another value, and the
-- patient id (a random uuid) cannot be recovered from it.
CREATE FUNCTION brand_pair_ref(p_home uuid, p_viewer uuid, p_patient uuid) RETURNS text
  LANGUAGE sql IMMUTABLE AS $$
  SELECT encode(sha256(convert_to(p_home::text || '|' || p_viewer::text || '|' || p_patient::text, 'UTF8')), 'hex')
$$;

-- May this patient be shown to a sibling branch at all? Stricter than patient.api.ConsentGateSql: in a default-deny clinic only
-- a data_processing grant counts (an emergency treatment basis does not carry to another clinic).
CREATE FUNCTION brand_directory_shareable(p_tenant uuid, p_patient uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = public AS $$
  SELECT EXISTS (
    SELECT 1 FROM patients p
     WHERE p.tenant_id = p_tenant AND p.id = p_patient
       AND p.status = 'active' AND NOT p.is_synthetic AND NOT p.brand_directory_opt_out AND NOT p.phone_invalid
       AND p.guardian_id IS NULL AND p.dob <= (current_date - interval '18 years')::date
       AND CASE WHEN (SELECT t.consent_default_deny FROM tenants t WHERE t.id = p.tenant_id)
           THEN COALESCE((SELECT c.withdrawn_at IS NULL FROM consents c
                           WHERE c.tenant_id = p.tenant_id AND c.patient_id = p.id AND c.consent_type = 'data_processing'
                           ORDER BY c.created_at DESC, c.id DESC LIMIT 1), false)
           ELSE NOT EXISTS (SELECT 1 FROM consents c
                             WHERE c.tenant_id = p.tenant_id AND c.patient_id = p.id AND c.consent_type = 'data_processing'
                               AND c.withdrawn_at IS NOT NULL) END)
$$;

-- What the caller's own clinic may do: the brand profile, whether p_actor is the brand's owner account on the built-in Owner
-- role (the PLT-022 rule), how many sibling branches the directory would reach, and p_actor's directory attempts (lookups and
-- refused links) in the last minute for the per-user limit. Reads the caller's own clinic only, so it runs as the caller.
CREATE FUNCTION brand_directory_state(p_actor uuid)
RETURNS TABLE (profile text, enabled boolean, is_brand_owner boolean, sibling_count int, recent_attempts int)
  LANGUAGE sql STABLE SET search_path = public AS $$
  SELECT COALESCE(b.chain_sharing_profile, 'NONE'),
         COALESCE(b.status = 'active' AND b.chain_sharing_profile = 'DEMOGRAPHICS', false),
         EXISTS (SELECT 1 FROM staff s JOIN roles r ON r.id = s.role_id AND r.tenant_id = s.tenant_id AND r.built_in
                   JOIN owners o ON o.id = b.owner_id
                  WHERE s.id = p_actor AND s.tenant_id = mine.id AND s.status = 'active'
                    AND (s.owner_id = o.id OR (s.owner_id IS NULL AND s.email = o.email))),
         (SELECT count(*)::int FROM tenants sib
           WHERE sib.brand_id = mine.brand_id AND sib.id <> mine.id AND sib.region_code = mine.region_code AND sib.status = 'active'),
         (SELECT count(*)::int FROM audit_log a
           WHERE a.tenant_id = mine.id AND a.actor_id = p_actor AND a.created_at > now() - interval '1 minute'
             AND a.action IN ('patient.brand_directory.searched', 'patient.brand_link.refused'))
    FROM tenants mine LEFT JOIN brands b ON b.id = mine.brand_id
   WHERE mine.id = app_tenant_id()
$$;

-- The owner switches the brand's sharing profile. Refused (false) unless p_actor is the brand's owner account on the built-in
-- Owner role of the caller's clinic. Audited in EVERY clinic of the brand, because it changes what each of them shares.
CREATE FUNCTION brand_set_sharing_profile(p_profile text, p_actor uuid, p_ip inet) RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  me uuid := app_tenant_id();
  v_brand uuid;
  v_old text;
  v_name text;
BEGIN
  IF me IS NULL OR p_profile NOT IN ('NONE', 'DEMOGRAPHICS') THEN RETURN false; END IF;
  SELECT b.id, b.chain_sharing_profile, s.name INTO v_brand, v_old, v_name
    FROM staff s JOIN roles r ON r.id = s.role_id AND r.tenant_id = s.tenant_id AND r.built_in
    JOIN tenants t ON t.id = s.tenant_id JOIN brands b ON b.id = t.brand_id JOIN owners o ON o.id = b.owner_id
   WHERE s.id = p_actor AND s.tenant_id = me AND s.status = 'active'
     AND (s.owner_id = o.id OR (s.owner_id IS NULL AND s.email = o.email))
     FOR UPDATE OF b;
  IF NOT FOUND THEN RETURN false; END IF;
  IF v_old = p_profile THEN RETURN true; END IF;
  UPDATE brands SET chain_sharing_profile = p_profile WHERE id = v_brand;
  INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id, before, after)
  SELECT t.id, CASE WHEN t.id = me THEN 'staff' ELSE 'system' END, CASE WHEN t.id = me THEN p_actor END, v_name, 'Owner', p_ip,
         'brand.chain_sharing_profile.changed', 'brand', v_brand,
         jsonb_build_object('profile', v_old), jsonb_build_object('profile', p_profile, 'changedInTenantId', me)
    FROM tenants t WHERE t.brand_id = v_brand ORDER BY t.id;
  RETURN true;
END $$;

-- Phone lookup across the sibling branches, one source clinic at a time. Returns demographics only (name, sex, year of birth).
-- With p_dob, a patient whose date of birth differs (or is known by year only) comes back as dob_mismatch with NO demographics:
-- the desk is told to check in person. Every candidate read writes phi_read_log (purpose brand_directory_lookup) in the source
-- clinic and in the searching clinic (there the patient column holds the pairwise reference as a uuid, never the source id),
-- and every call writes one audit_log row in the searching clinic, matches or not (it also feeds the per-user limit).
CREATE FUNCTION brand_directory_lookup(p_phone text, p_dob date, p_actor uuid, p_actor_name text, p_actor_role text, p_ip inet, p_request_id text)
RETURNS TABLE (source_tenant_id uuid, branch_label text, source_patient_ref text, display_name text, sex text, birth_year int, dob_mismatch boolean)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  me uuid := app_tenant_id();
  s record;
  p record;
  v_ref text;
  v_found int := 0;
  v_mismatch int := 0;
BEGIN
  IF me IS NULL OR p_actor IS NULL OR p_phone IS NULL THEN RETURN; END IF;
  IF NOT EXISTS (SELECT 1 FROM tenants mine JOIN brands b ON b.id = mine.brand_id
                  WHERE mine.id = me AND b.status = 'active' AND b.chain_sharing_profile = 'DEMOGRAPHICS') THEN
    RETURN;
  END IF;
  FOR s IN SELECT sib.id, COALESCE(NULLIF(sib.branch_label, ''), sib.name) AS label
             FROM tenants mine JOIN tenants sib ON sib.brand_id = mine.brand_id AND sib.id <> mine.id
                                               AND sib.region_code = mine.region_code AND sib.status = 'active'
            WHERE mine.id = me ORDER BY sib.created_at, sib.id
  LOOP
    FOR p IN SELECT pt.id, pt.name, pt.gender, pt.dob, pt.dob_year_only FROM patients pt
              WHERE pt.tenant_id = s.id AND pt.phone = p_phone AND brand_directory_shareable(pt.tenant_id, pt.id)
              ORDER BY pt.created_at, pt.id LIMIT 5
    LOOP
      v_ref := brand_pair_ref(s.id, me, p.id);
      INSERT INTO phi_read_log (tenant_id, actor_type, actor_id, patient_id, resource_type, resource_id, endpoint, purpose_code, ip, request_id)
      VALUES (s.id, 'system', p_actor, p.id, 'brand_directory', me, '/v1/patients/brand-directory', 'brand_directory_lookup', p_ip, p_request_id),
             (me, 'staff', p_actor, substr(v_ref, 1, 32)::uuid, 'brand_directory', s.id, '/v1/patients/brand-directory', 'brand_directory_lookup', p_ip, p_request_id);
      v_found := v_found + 1;
      dob_mismatch := p_dob IS NOT NULL AND (p.dob_year_only OR p.dob <> p_dob);
      IF dob_mismatch THEN
        v_mismatch := v_mismatch + 1;
        source_tenant_id := NULL; branch_label := NULL; source_patient_ref := NULL; display_name := NULL; sex := NULL; birth_year := NULL;
      ELSE
        source_tenant_id := s.id; branch_label := s.label; source_patient_ref := v_ref;
        display_name := p.name; sex := p.gender; birth_year := extract(year FROM p.dob)::int;
      END IF;
      RETURN NEXT;
    END LOOP;
  END LOOP;
  INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id, before, after)
  VALUES (me, 'staff', p_actor, p_actor_name, p_actor_role, p_ip, 'patient.brand_directory.searched', 'brand_directory', NULL, NULL,
          jsonb_build_object('candidates', v_found, 'dobMismatches', v_mismatch, 'dobGiven', p_dob IS NOT NULL, 'requestId', p_request_id));
END $$;

-- Link a sibling branch's patient to a patient of the caller's clinic. The caller must present the pairwise reference from a
-- lookup AND the exact mobile AND the exact date of birth: matching is never fuzzy across clinics. The local side is the
-- caller's active patient with the same mobile, date of birth and name when one exists (is_new = false, nothing is copied),
-- otherwise the new row p_new_patient that the caller inserts in the same transaction from the returned demographics.
-- On 'ok' the source clinic gets its link row, an audit_log row and a phi_read_log row and the searching clinic its
-- phi_read_log row. Every other outcome writes nothing.
CREATE FUNCTION brand_link_create(p_source_tenant uuid, p_source_ref text, p_phone text, p_dob date, p_new_patient uuid,
                                  p_actor uuid, p_ip inet, p_request_id text)
RETURNS TABLE (outcome text, local_patient_id uuid, is_new boolean, src_name text, src_phone text, src_dob date, src_gender text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
#variable_conflict use_column
DECLARE
  me uuid := app_tenant_id();
  v_label text;
  v_local uuid;
  p record;
BEGIN
  outcome := 'disabled';
  IF me IS NULL OR p_actor IS NULL OR p_new_patient IS NULL THEN RETURN NEXT; RETURN; END IF;
  SELECT COALESCE(NULLIF(mine.branch_label, ''), mine.name) INTO v_label
    FROM tenants mine JOIN brands b ON b.id = mine.brand_id
   WHERE mine.id = me AND b.status = 'active' AND b.chain_sharing_profile = 'DEMOGRAPHICS';
  IF NOT FOUND THEN RETURN NEXT; RETURN; END IF;

  outcome := 'not_found';  -- a clinic of another brand or region answers like one that does not exist
  IF NOT EXISTS (SELECT 1 FROM tenants mine JOIN tenants sib ON sib.brand_id = mine.brand_id AND sib.id <> mine.id
                                                           AND sib.region_code = mine.region_code AND sib.status = 'active'
                  WHERE mine.id = me AND sib.id = p_source_tenant) THEN
    RETURN NEXT; RETURN;
  END IF;
  SELECT pt.id, pt.name, pt.phone, pt.dob, pt.gender, pt.dob_year_only, pt.brand_directory_opt_out INTO p
    FROM patients pt
   WHERE pt.tenant_id = p_source_tenant AND pt.phone = p_phone AND brand_pair_ref(pt.tenant_id, me, pt.id) = p_source_ref;
  IF NOT FOUND THEN RETURN NEXT; RETURN; END IF;
  IF p.brand_directory_opt_out THEN outcome := 'opted_out'; RETURN NEXT; RETURN; END IF;
  IF NOT brand_directory_shareable(p_source_tenant, p.id) THEN RETURN NEXT; RETURN; END IF;
  IF p_dob IS NULL OR p.dob_year_only OR p.dob <> p_dob THEN outcome := 'dob_mismatch'; RETURN NEXT; RETURN; END IF;
  SELECT mp.id INTO v_local FROM patients mp
   WHERE mp.tenant_id = me AND mp.phone = p.phone AND mp.dob = p.dob AND NOT mp.dob_year_only AND mp.status = 'active'
     AND lower(btrim(mp.name)) = lower(btrim(p.name))
   ORDER BY mp.created_at, mp.id LIMIT 1;
  is_new := v_local IS NULL;
  local_patient_id := COALESCE(v_local, p_new_patient);
  IF EXISTS (SELECT 1 FROM patient_branch_links l
              WHERE (l.tenant_id = p_source_tenant AND l.patient_id = p.id AND l.linked_tenant_id = me)
                 OR (l.tenant_id = me AND l.patient_id = local_patient_id AND l.linked_tenant_id = p_source_tenant)) THEN
    outcome := 'already_linked'; local_patient_id := NULL; is_new := NULL; RETURN NEXT; RETURN;
  END IF;

  INSERT INTO patient_branch_links (tenant_id, patient_id, linked_tenant_id, linked_patient_ref, basis)
  VALUES (p_source_tenant, p.id, me, brand_pair_ref(me, p_source_tenant, local_patient_id), 'desk_phone_dob');
  INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id, before, after)
  VALUES (p_source_tenant, 'system', NULL, 'Sibling branch: ' || v_label, 'Brand directory', p_ip, 'patient.branch_link.created', 'patient', p.id, NULL,
          jsonb_build_object('linkedTenantId', me, 'basis', 'desk_phone_dob', 'demographicsCopied', is_new, 'requestId', p_request_id));
  INSERT INTO phi_read_log (tenant_id, actor_type, actor_id, patient_id, resource_type, resource_id, endpoint, purpose_code, ip, request_id)
  VALUES (p_source_tenant, 'system', p_actor, p.id, 'brand_link_import', me, '/v1/patients/brand-links', 'brand_directory_lookup', p_ip, p_request_id),
         (me, 'staff', p_actor, local_patient_id, 'brand_link_import', p_source_tenant, '/v1/patients/brand-links', 'brand_directory_lookup', p_ip, p_request_id);
  outcome := 'ok';
  IF is_new THEN src_name := p.name; src_phone := p.phone; src_dob := p.dob; src_gender := p.gender; END IF;
  RETURN NEXT;
END $$;

-- End the links of the caller's own patient (one link, or all when p_link is null): the caller's row and the matching row in
-- the other clinic are deleted, so both patient records stand alone, and the other clinic gets an audit_log row (the caller
-- writes its own). Needs no sharing profile: a link can always be ended. Returns the other clinics' ids.
CREATE FUNCTION brand_link_end(p_patient uuid, p_link uuid, p_initiator text, p_ip inet) RETURNS SETOF uuid
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  me uuid := app_tenant_id();
  v_label text;
  l record;
  v_peer uuid;
BEGIN
  IF me IS NULL OR p_patient IS NULL THEN RETURN; END IF;
  SELECT COALESCE(NULLIF(t.branch_label, ''), t.name) INTO v_label FROM tenants t WHERE t.id = me;
  FOR l IN DELETE FROM patient_branch_links k
            WHERE k.tenant_id = me AND k.patient_id = p_patient AND (p_link IS NULL OR k.id = p_link)
        RETURNING k.linked_tenant_id
  LOOP
    v_peer := NULL;
    DELETE FROM patient_branch_links k
     WHERE k.tenant_id = l.linked_tenant_id AND k.linked_tenant_id = me
       AND k.linked_patient_ref = brand_pair_ref(me, l.linked_tenant_id, p_patient)
    RETURNING k.patient_id INTO v_peer;
    IF v_peer IS NOT NULL THEN
      INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address, action, entity_type, entity_id, before, after)
      VALUES (l.linked_tenant_id, 'system', NULL, 'Sibling branch: ' || v_label, 'Brand directory', p_ip, 'patient.branch_link.ended', 'patient', v_peer,
              jsonb_build_object('linkedTenantId', me), jsonb_build_object('endedBy', p_initiator));
    END IF;
    RETURN NEXT l.linked_tenant_id;
  END LOOP;
END $$;

REVOKE ALL ON FUNCTION brand_set_sharing_profile(text, uuid, inet) FROM PUBLIC;
REVOKE ALL ON FUNCTION brand_directory_lookup(text, date, uuid, text, text, inet, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION brand_link_create(uuid, text, text, date, uuid, uuid, inet, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION brand_link_end(uuid, uuid, text, inet) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION brand_set_sharing_profile(text, uuid, inet) TO nabd_app;
    GRANT EXECUTE ON FUNCTION brand_directory_lookup(text, date, uuid, text, text, inet, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION brand_link_create(uuid, text, text, date, uuid, uuid, inet, text) TO nabd_app;
    GRANT EXECUTE ON FUNCTION brand_link_end(uuid, uuid, text, inet) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
