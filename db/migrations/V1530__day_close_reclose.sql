-- FIN-005 (QA-25 / MF-68): a reopened day can be closed again. day_closes keeps the current state (close_count counts
-- the closes); day_close_events keeps every close and reopen (expected/counted/variance per close, the reason as the
-- reopen note). Cash tolerance becomes the clinic policy 'cash_variance_tolerance' (minor units of the tenant
-- currency); absent row = built-in default per currency (DayCloseService), like FEF-005's idle lock.
SET lock_timeout = '5s';

ALTER TABLE day_closes ADD COLUMN close_count int NOT NULL DEFAULT 1 CHECK (close_count >= 1);

CREATE TABLE day_close_events (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  day_close_id   uuid NOT NULL REFERENCES day_closes(id),
  action         text NOT NULL CHECK (action IN ('close', 'reopen')),
  expected_cash  numeric(12,2),
  counted_cash   numeric(12,2),
  variance       numeric(12,2),
  note           text,
  actor_id       uuid NOT NULL REFERENCES staff(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK ((action = 'close') = (expected_cash IS NOT NULL AND counted_cash IS NOT NULL AND variance IS NOT NULL))
);
CREATE INDEX day_close_events_close_idx ON day_close_events (tenant_id, day_close_id, created_at);

-- Backfill: one close event per existing row, plus its reopen when the row is reopened. RLS is forced and the
-- migrator has no BYPASSRLS, so scope each tenant the way the API does (same as V1232).
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    INSERT INTO day_close_events (tenant_id, day_close_id, action, expected_cash, counted_cash, variance, note, actor_id, created_at)
    SELECT tenant_id, id, 'close', expected_cash, counted_cash, variance, note, closed_by, closed_at
    FROM day_closes WHERE tenant_id = t.id;
    INSERT INTO day_close_events (tenant_id, day_close_id, action, note, actor_id, created_at)
    SELECT tenant_id, id, 'reopen', reopen_reason, reopened_by, reopened_at
    FROM day_closes WHERE tenant_id = t.id AND status = 'reopened' AND reopened_by IS NOT NULL;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;

ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes',
  'cash_variance_tolerance'
));

-- ADR-07 per-command policies: reads over app.visible_tenant_ids, writes only for app.tenant_id. A policy naming
-- visible_tenant_ids first makes enforce_tenant_rls build the widened read policy (V211).
CREATE POLICY adr07_read ON day_closes FOR SELECT USING (tenant_id = ANY (app_visible_tenant_ids()));
SELECT enforce_tenant_rls('day_closes');
CREATE POLICY adr07_read ON day_close_events FOR SELECT USING (tenant_id = ANY (app_visible_tenant_ids()));
SELECT enforce_tenant_rls('day_close_events');
