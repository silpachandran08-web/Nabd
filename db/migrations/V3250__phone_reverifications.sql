SET lock_timeout = '5s';
-- COM-041: Core's view of a WhatsApp number that must re-verify before records or links are served. The Patient Access Edge owns
-- the chat flow (its own phone_verifications: attempts, lock) and reports each step here; the desk recording a number change writes
-- here directly. Secure links (IDC-015) are held while a recipient's number is not verified. Keyed by the SHA-256 of the number's
-- digits, the same hash the household lookup uses; no number, no name.
CREATE TABLE phone_reverifications (
  tenant_id    uuid NOT NULL REFERENCES tenants (id),
  phone_hash   text NOT NULL CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
  state        text NOT NULL CHECK (state IN ('verified', 'needs_reverify', 'locked')),
  reason       text CHECK (reason IN ('changed_number', 'wa_id_changed', 'silence', 'desk_change')),
  locked_until timestamptz,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, phone_hash)
);
SELECT enforce_tenant_rls('phone_reverifications');

-- The desk is alerted through the IDC-010 desk task list: a number locked after 3 failed attempts.
ALTER TABLE patient_desk_tasks DROP CONSTRAINT patient_desk_tasks_kind_check;
ALTER TABLE patient_desk_tasks ADD CONSTRAINT patient_desk_tasks_kind_check
  CHECK (kind IN ('confirm_assisted_consent', 'phone_reverify_locked')) NOT VALID;
ALTER TABLE patient_desk_tasks VALIDATE CONSTRAINT patient_desk_tasks_kind_check;
