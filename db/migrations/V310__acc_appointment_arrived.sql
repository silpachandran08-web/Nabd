-- ACC-003: an appointment is checked in once and then shows 'arrived'.
SET LOCK_TIMEOUT = '5s';
ALTER TABLE appointments DROP CONSTRAINT IF EXISTS appointments_status_check;
ALTER TABLE appointments ADD CONSTRAINT appointments_status_check
  CHECK (status IN ('scheduled','arrived','cancelled','completed','no_show')) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT appointments_status_check;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS arrived_at timestamptz;

-- Pre-fix duplicates are flagged, never deleted: notes or invoices may point at them.
ALTER TABLE queue_entries ADD COLUMN IF NOT EXISTS legacy_duplicate boolean NOT NULL DEFAULT false;
WITH ranked AS (
  SELECT id, row_number() OVER (
           PARTITION BY tenant_id, appointment_id
           ORDER BY (status IN ('completed','no_show','transferred_out')), created_at, id) AS rn
    FROM queue_entries WHERE appointment_id IS NOT NULL)
UPDATE queue_entries q SET legacy_duplicate = true FROM ranked r WHERE q.id = r.id AND r.rn > 1;

-- A still-scheduled appointment that already has a live (non-terminal) visit is arrived.
UPDATE appointments a SET status = 'arrived', arrived_at = COALESCE(a.arrived_at, q.created_at)
  FROM queue_entries q
 WHERE q.appointment_id = a.id AND q.tenant_id = a.tenant_id AND NOT q.legacy_duplicate
   AND q.status NOT IN ('completed','no_show','transferred_out') AND a.status = 'scheduled';
