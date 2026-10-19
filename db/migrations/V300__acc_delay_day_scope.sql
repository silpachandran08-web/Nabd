-- ACC-001: a doctor delay lapses at the end of its clinic day; delay length is bounded.
ALTER TABLE doctor_delays ADD COLUMN IF NOT EXISTS clinic_date date;
UPDATE doctor_delays dd SET clinic_date = (dd.announced_at AT TIME ZONE COALESCE(t.timezone, 'UTC'))::date
  FROM tenants t WHERE t.id = dd.tenant_id AND dd.clinic_date IS NULL;
ALTER TABLE doctor_delays ALTER COLUMN clinic_date SET NOT NULL;
-- legacy rows above 240 are clamped so the new bound can be enforced
UPDATE doctor_delays SET delay_minutes = 240 WHERE delay_minutes > 240;
ALTER TABLE doctor_delays DROP CONSTRAINT IF EXISTS chk_doctor_delays_minutes;
ALTER TABLE doctor_delays ADD CONSTRAINT chk_doctor_delays_minutes CHECK (delay_minutes BETWEEN 1 AND 240);
DROP INDEX IF EXISTS uq_doctor_delays_active;
CREATE UNIQUE INDEX IF NOT EXISTS uq_doctor_delays_active_day
  ON doctor_delays (tenant_id, doctor_id, clinic_date) WHERE cleared_at IS NULL;
