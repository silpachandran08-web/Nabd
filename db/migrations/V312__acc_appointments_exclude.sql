-- ACC-004: overlapping bookings are impossible at the database, not just rejected by the service.
-- Replaces uq_appointments_doctor_slot (same start only) with a range exclusion: 10:00-10:15 and 10:05-10:20
-- for one doctor can no longer coexist. Token-mode rows (booking_kind='token', ACC-011) are exempt.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Pre-flight: abort with the offending pairs (ids only, no PHI) so ops can resolve them by hand.
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does.
DO $$
DECLARE
  t record;
  pairs text;
  report text := '';
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    SELECT string_agg(a.id || ' x ' || b.id, ', ') INTO pairs
      FROM appointments a JOIN appointments b
        ON a.doctor_id = b.doctor_id AND a.id < b.id
       AND a.status = 'scheduled' AND b.status = 'scheduled'
       AND tstzrange(a.start_time, a.end_time, '[)') && tstzrange(b.start_time, b.end_time, '[)');
    IF pairs IS NOT NULL THEN
      report := report || format(E'\n tenant %s: %s', t.id, pairs);
    END IF;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
  IF report <> '' THEN
    RAISE EXCEPTION 'ACC-004 pre-flight: overlapping scheduled appointments, resolve by hand (see runbook):%', report;
  END IF;
END $$;

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS booking_kind text NOT NULL DEFAULT 'slot'
  CHECK (booking_kind IN ('slot','token'));

ALTER TABLE appointments ADD CONSTRAINT ex_appointments_doctor_overlap
  EXCLUDE USING gist (tenant_id WITH =, doctor_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
  WHERE (booking_kind = 'slot' AND status IN ('scheduled','arrived'));

DROP INDEX IF EXISTS uq_appointments_doctor_slot;
