SET lock_timeout = '5s';
-- ADM-007: the setup wizard has nine steps (profile, preset, doctors, prices, whatsapp, tax, dpa, import, go_live), each holding a
-- small draft with an optimistic version. Old steps are mapped: charges->prices, schedule->doctors (merged: done only when both were
-- done), departments->preset; welcome and pharmacy are dropped. Steps new to a tenant (dpa, import, ...) are seeded on first read.
-- The table keeps its own-clinic-only policies (ADR-07: the wizard is per clinic, no sibling read).
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
ALTER TABLE clinic_setup_progress DROP CONSTRAINT IF EXISTS clinic_setup_progress_step_check;
ALTER TABLE clinic_setup_progress ADD COLUMN draft jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE clinic_setup_progress ADD COLUMN version integer NOT NULL DEFAULT 0;

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    DELETE FROM clinic_setup_progress WHERE tenant_id = t.id AND step IN ('welcome', 'pharmacy');
    -- schedule with no doctors row is simply renamed; with both, the pair is merged into doctors
    UPDATE clinic_setup_progress s SET step = 'doctors'
     WHERE s.tenant_id = t.id AND s.step = 'schedule'
       AND NOT EXISTS (SELECT 1 FROM clinic_setup_progress d WHERE d.tenant_id = t.id AND d.step = 'doctors');
    UPDATE clinic_setup_progress d
       SET status = CASE WHEN d.status = 'done' AND s.status = 'done' THEN 'done'
                         WHEN d.status = 'pending' OR s.status = 'pending' THEN 'pending' ELSE 'skipped' END,
           done_at = CASE WHEN d.status = 'done' AND s.status = 'done' THEN d.done_at END,
           skipped_at = CASE WHEN d.status <> 'pending' AND s.status <> 'pending' AND NOT (d.status = 'done' AND s.status = 'done') THEN COALESCE(d.skipped_at, s.skipped_at, now()) END
      FROM clinic_setup_progress s
     WHERE d.tenant_id = t.id AND s.tenant_id = t.id AND d.step = 'doctors' AND s.step = 'schedule';
    DELETE FROM clinic_setup_progress WHERE tenant_id = t.id AND step = 'schedule';
    UPDATE clinic_setup_progress SET step = 'prices' WHERE tenant_id = t.id AND step = 'charges';
    UPDATE clinic_setup_progress SET step = 'preset' WHERE tenant_id = t.id AND step = 'departments';
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

ALTER TABLE clinic_setup_progress ADD CONSTRAINT clinic_setup_progress_step_check
  CHECK (step IN ('profile', 'preset', 'doctors', 'prices', 'whatsapp', 'tax', 'dpa', 'import', 'go_live')) NOT VALID;
ALTER TABLE clinic_setup_progress VALIDATE CONSTRAINT clinic_setup_progress_step_check;

-- drop the brand-widened read policies first: enforce_tenant_rls keeps a table widened when it finds widening in what exists
DO $$
DECLARE
  p record;
BEGIN
  FOR p IN SELECT polname FROM pg_policy WHERE polrelid = 'clinic_setup_progress'::regclass LOOP
    EXECUTE format('DROP POLICY %I ON clinic_setup_progress', p.polname);
  END LOOP;
END $$;
SELECT enforce_tenant_rls('clinic_setup_progress');
