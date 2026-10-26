SET lock_timeout = '5s';
-- ACC-012: walk-in capacity kept back per session (grid slots online channels never offer) and the "why here" of a queue entry.
CREATE TABLE session_reserved_slots (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL,
  working_hours_id uuid NOT NULL REFERENCES doctor_working_hours(id) ON DELETE CASCADE,
  slot_minute      int NOT NULL CHECK (slot_minute BETWEEN 0 AND 1439),
  UNIQUE (working_hours_id, slot_minute)
);
SELECT enforce_tenant_rls('session_reserved_slots');

ALTER TABLE queue_entries
  ADD COLUMN order_reason text NULL,
  ADD COLUMN called_seq int NULL, -- nth call-next of the doctor's day: the interleave counter reads it

  ADD COLUMN placed_as_walkin_late boolean NOT NULL DEFAULT false;

-- the session's walk-in rule: 'interleave' = one walk-in after every walkin_ratio booked patients (1:2..1:4),
-- 'reserved_only' = walk-ins only into reserved slots, 'none' = walk-ins after all booked patients (desk override).
-- A booked patient later than late_grace_minutes after the slot is placed as a walk-in.
ALTER TABLE doctor_working_hours
  ADD COLUMN walkin_mode text NOT NULL DEFAULT 'interleave' CHECK (walkin_mode IN ('interleave','reserved_only','none')),
  ADD COLUMN walkin_ratio int NOT NULL DEFAULT 3 CHECK (walkin_ratio BETWEEN 2 AND 4),
  ADD COLUMN late_grace_minutes int NOT NULL DEFAULT 15 CHECK (late_grace_minutes BETWEEN 0 AND 120);
