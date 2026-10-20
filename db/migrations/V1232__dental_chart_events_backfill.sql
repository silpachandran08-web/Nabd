-- CLN-006 (MF-52): copy every historic dental chart change from audit_log into dental_chart_events.
-- Action mapping: a standard tooth's create/update -> 'set'; a supernumerary tooth's create/update/delete ->
-- supernumerary_added/_changed/_removed. Before/after snapshots and timestamps are copied verbatim.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
SET lock_timeout = '5s';

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    INSERT INTO dental_chart_events (tenant_id, patient_id, entry_id, tooth_number, event, before, after, actor_id, occurred_at)
    SELECT a.tenant_id, e.patient_id, a.entity_id,
           COALESCE(a.after ->> 'toothNumber', a.before ->> 'toothNumber')::int,
           CASE
             WHEN NOT COALESCE((COALESCE(a.after, a.before) ->> 'isSupernumerary')::boolean, false) THEN 'set'
             WHEN a.action = 'dental_chart.create' THEN 'supernumerary_added'
             WHEN a.action = 'dental_chart.delete' THEN 'supernumerary_removed'
             ELSE 'supernumerary_changed'
           END,
           a.before, a.after, a.actor_id, a.created_at
    FROM audit_log a
    LEFT JOIN dental_chart_entries e ON e.tenant_id = a.tenant_id AND e.id = a.entity_id
    WHERE a.tenant_id = t.id AND a.entity_type = 'dental_chart_entries' AND a.entity_id IS NOT NULL
      AND a.actor_id IS NOT NULL
    ORDER BY a.id;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
