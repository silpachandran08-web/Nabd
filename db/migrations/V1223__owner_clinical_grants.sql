-- CLN-005 (QA-17): the RBAC Matrix gives Owner / Manager -> Clinical Records: View Y, Create N, Edit N, Delete Y,
-- Approve Y, Export Y. Provisioning seeded fullGrant('clinical'); correct the built-in Owner role of existing tenants.
-- Owner-doctors keep create/edit: a tenant whose Owner role carries a registered clinician (is_clinician, V1220) is
-- left unchanged until PLT-016's Owner-doctor preset (T-305) can take them. Each corrected role gets an audit row
-- (actor 'system'; the V14 trigger chains it).
SET lock_timeout = '5s';

DO $$
DECLARE
  t record;
  r record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    FOR r IN
      UPDATE roles ro SET grants = (
               SELECT jsonb_agg(CASE WHEN x.g->>'module' = 'clinical'
                                     THEN x.g || '{"create": false, "edit": false}'::jsonb ELSE x.g END ORDER BY x.ord)
                 FROM jsonb_array_elements(ro.grants) WITH ORDINALITY AS x(g, ord))
       WHERE ro.tenant_id = t.id AND ro.built_in AND ro.name = 'Owner'
         AND EXISTS (SELECT 1 FROM jsonb_array_elements(ro.grants) g
                      WHERE g->>'module' = 'clinical'
                        AND (coalesce((g->>'create')::boolean, false) OR coalesce((g->>'edit')::boolean, false)))
         AND NOT EXISTS (SELECT 1 FROM staff s WHERE s.tenant_id = t.id AND s.role_id = ro.id AND s.is_clinician)
      RETURNING ro.id
    LOOP
      INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id,
                             before, after)
      VALUES (t.id, 'system', NULL, 'System', 'System', 'role.owner_clinical_grants_corrected', 'role', r.id,
              '{"clinical": {"create": true, "edit": true}}'::jsonb,
              '{"clinical": {"create": false, "edit": false}, "source": "RBAC Matrix (CLN-005)"}'::jsonb);
    END LOOP;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
