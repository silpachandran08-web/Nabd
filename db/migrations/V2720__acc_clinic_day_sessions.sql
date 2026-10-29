SET lock_timeout = '5s';
-- ACC-005: a clinic day starts at tenants.day_start_minute (default 04:00) so an evening session that runs past midnight
-- stays on one queue_date. A working-hours block may end after midnight (end_time < start_time = next calendar day; the
-- service refuses one that ends after the day start). doctor_leave may cover only part of a (single) clinic day.
ALTER TABLE tenants
  ADD COLUMN day_start_minute int NOT NULL DEFAULT 240 CHECK (day_start_minute BETWEEN 0 AND 359);

ALTER TABLE doctor_working_hours DROP CONSTRAINT IF EXISTS doctor_working_hours_check;
ALTER TABLE doctor_working_hours ADD CONSTRAINT doctor_working_hours_not_empty CHECK (end_time <> start_time) NOT VALID;
ALTER TABLE doctor_working_hours VALIDATE CONSTRAINT doctor_working_hours_not_empty;

ALTER TABLE doctor_leave
  ADD COLUMN start_time time NULL,
  ADD COLUMN end_time time NULL;
ALTER TABLE doctor_leave ADD CONSTRAINT doctor_leave_window_pair CHECK ((start_time IS NULL) = (end_time IS NULL)) NOT VALID;
ALTER TABLE doctor_leave VALIDATE CONSTRAINT doctor_leave_window_pair;
ALTER TABLE doctor_leave ADD CONSTRAINT doctor_leave_window_nonempty CHECK (start_time IS NULL OR end_time <> start_time) NOT VALID;
ALTER TABLE doctor_leave VALIDATE CONSTRAINT doctor_leave_window_nonempty;

-- A clinic that already has hours starting before 04:00 (a 24-hour clinic) keeps its midnight day: day start 00:00 changes nothing for it.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    IF EXISTS (SELECT 1 FROM doctor_working_hours WHERE tenant_id = t.id AND start_time < time '04:00') THEN
      UPDATE tenants SET day_start_minute = 0 WHERE id = t.id;
    END IF;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
