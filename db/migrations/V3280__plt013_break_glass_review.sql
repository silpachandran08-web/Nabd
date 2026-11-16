SET lock_timeout = '5s';
-- PLT-013: the owner reviews every ended break-glass grant. The device label the client sent (X-Device-Label, display only,
-- never used for a security decision), the review outcome and its note (the incident note when 'not_justified'; free text,
-- may name a condition), and when the one 24 h review reminder went out.
ALTER TABLE break_glass_grants
  ADD COLUMN device_label       text CHECK (char_length(device_label) <= 64),
  ADD COLUMN reviewed_by        uuid REFERENCES staff (id),
  ADD COLUMN reviewed_at        timestamptz,
  ADD COLUMN review_outcome     text CHECK (review_outcome IN ('justified', 'not_justified')),
  ADD COLUMN review_note        text CHECK (char_length(review_note) <= 1000),
  ADD COLUMN review_reminded_at timestamptz;

-- migration-lint: allow MIG-001 break_glass_grants holds a handful of rows per clinic; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX idx_break_glass_unreviewed ON break_glass_grants (tenant_id, reviewed_at) WHERE reviewed_at IS NULL;

-- 'not justified' can take clinical:breakGlass away from one staff member without touching their role (everyone else on it keeps it).
ALTER TABLE staff ADD COLUMN break_glass_revoked_at timestamptz;

-- the reminder is a second in-app alert for the same grant and owner
ALTER TABLE break_glass_alerts ADD COLUMN kind text NOT NULL DEFAULT 'activated' CHECK (kind IN ('activated', 'review_reminder'));
ALTER TABLE break_glass_alerts DROP CONSTRAINT IF EXISTS break_glass_alerts_grant_id_owner_staff_id_key;
ALTER TABLE break_glass_alerts ADD CONSTRAINT uq_break_glass_alerts_kind UNIQUE (grant_id, owner_staff_id, kind);

-- The reminder job finds due grants across clinics before it enters each clinic's scope (V3061 precedent): ids only, no PHI.
-- It does not claim them: each is stamped in its own tenant transaction together with the alerts, so a failure is retried.
CREATE FUNCTION due_break_glass_reminders(p_now timestamptz)
RETURNS TABLE (tenant_id uuid, id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT g.tenant_id, g.id FROM break_glass_grants g
  WHERE g.reviewed_at IS NULL AND g.review_reminded_at IS NULL
    AND LEAST(COALESCE(g.deactivated_at, g.expires_at), g.expires_at) <= p_now - interval '24 hours'
  ORDER BY g.activated_at LIMIT 500;
$$;
REVOKE ALL ON FUNCTION due_break_glass_reminders(timestamptz) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION due_break_glass_reminders(timestamptz) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
