-- E10 Queue, Scheduling & Availability (NB-099): a freed appointment slot is offered to the
-- oldest waiting entry for that doctor; the offer expires after 15 minutes and, since there's no
-- background-job platform to expire it the instant that happens, the next read (list/accept)
-- lazily closes it out and re-offers the same slot to whoever is next in line — the same
-- "compute on demand" shape as package expiry and delegation/break-glass expiry elsewhere.
CREATE TABLE waitlist_entries (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  doctor_id          uuid NOT NULL REFERENCES staff(id),
  patient_id         uuid NOT NULL REFERENCES patients(id),
  joined_at          timestamptz NOT NULL DEFAULT now(),
  status             text NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting','offered','booked','expired','cancelled')),
  offered_slot_start timestamptz,
  offer_expires_at   timestamptz,
  booked_appointment_id uuid REFERENCES appointments(id),
  -- one-directional on purpose: an offered row must carry slot info, but expired/booked rows keep
  -- theirs for history rather than nulling it out on the way out of 'offered'.
  CHECK (status != 'offered' OR (offered_slot_start IS NOT NULL AND offer_expires_at IS NOT NULL))
);
ALTER TABLE waitlist_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON waitlist_entries
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- FIFO scan for "who's next": one waiting/offered row for a doctor at a time is the common case.
CREATE INDEX idx_waitlist_doctor_status ON waitlist_entries (doctor_id, status, joined_at);
-- a patient can only be on one doctor's active waitlist once
CREATE UNIQUE INDEX uq_waitlist_active_membership ON waitlist_entries (doctor_id, patient_id)
  WHERE status IN ('waiting', 'offered');
