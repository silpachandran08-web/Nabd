-- Reconciles the schema with the v2 Implementation Task Plan (349 tasks,
-- up from 202). Scope of this migration is limited to changes that hit
-- already-built modules (Auth, RBAC, Patients, Queue) — the many net-new
-- epics (Caregiver/Family, Messaging Platform, Patient WhatsApp Surface,
-- Aggregator, expanded Platform Console, Pharmacy, ...) are out of scope
-- here; they're greenfield work, not drift to reconcile.

-- ── NB-002: region is locked at provisioning, not just chosen there ──
CREATE OR REPLACE FUNCTION prevent_region_change() RETURNS trigger AS $$
BEGIN
  IF NEW.region IS DISTINCT FROM OLD.region THEN
    RAISE EXCEPTION 'tenants.region is immutable after provisioning (NB-002)';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_region_immutable BEFORE UPDATE ON tenants
  FOR EACH ROW EXECUTE FUNCTION prevent_region_change();
-- ponytail: "rejected" is enforced (the trigger raises); "audited" is not —
-- there's still no audit-trail table (NB-182-equivalent) to write to. The
-- raised exception does land in the Postgres server log with statement
-- context in the meantime.

-- ── NB-040/041/042/046: WhatsApp OTP + short PIN + authenticator app.
-- NFR-06 rules out SSO and hardware keys; password isn't in the three
-- supported methods either, so it's replaced rather than kept alongside. ──
ALTER TABLE staff
  ADD COLUMN mobile_phone           citext,
  ADD COLUMN pin_hash                text,        -- Argon2id, same encoder as the old password_hash
  ADD COLUMN email_verified          boolean NOT NULL DEFAULT false,
  ADD COLUMN mobile_verified         boolean NOT NULL DEFAULT false,
  ADD COLUMN whatsapp_otp_hash       text,         -- sha256(otp); short-lived, mirrors invite_token_hash
  ADD COLUMN whatsapp_otp_expires_at timestamptz;

ALTER TABLE staff DROP COLUMN password_hash;

CREATE UNIQUE INDEX idx_staff_tenant_mobile ON staff (tenant_id, mobile_phone) WHERE mobile_phone IS NOT NULL;

-- ── NB-053: consent checked at query level, not the UI ──
CREATE TABLE consents (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  patient_id   uuid NOT NULL REFERENCES patients(id),
  consent_type text NOT NULL CHECK (consent_type IN ('treatment', 'data_processing', 'messaging')),
  granted_at   timestamptz NOT NULL DEFAULT now(),
  withdrawn_at timestamptz,
  version      int NOT NULL DEFAULT 1,
  created_at   timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE consents ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON consents
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_consents_patient ON consents (patient_id, consent_type);
-- ponytail: no consent-capture-at-registration flow yet (that's its own
-- CMP-* backlog item) — only withdrawal is modelled, which is the side that
-- actually gates data per NB-053's acceptance criteria. A patient with no
-- consent rows at all is treated as not-withdrawn (visible), not as
-- unconsented (hidden); tighten this once capture exists if that's wrong.

-- ── NB-072: merge must be reversible for 30 days and fully audited ──
CREATE TABLE patient_merges (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id             uuid NOT NULL REFERENCES tenants(id),
  duplicate_patient_id  uuid NOT NULL REFERENCES patients(id),
  survivor_patient_id   uuid NOT NULL REFERENCES patients(id),
  merged_by             uuid NOT NULL REFERENCES staff(id),
  merged_at             timestamptz NOT NULL DEFAULT now(),
  reversed_at           timestamptz,
  reversed_by           uuid REFERENCES staff(id)
);
ALTER TABLE patient_merges ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_merges
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_patient_merges_duplicate ON patient_merges (duplicate_patient_id);

-- ── NB-098: smart overbooking protection — a session cap shared by
-- scheduled appointments and walk-ins together ──
ALTER TABLE doctor_working_hours ADD COLUMN max_patients int; -- null = uncapped

-- ── NB-094: queue wireframe now lists "vitals pending/done" as two states ──
ALTER TABLE queue_entries DROP CONSTRAINT queue_entries_status_check;
ALTER TABLE queue_entries ADD CONSTRAINT queue_entries_status_check CHECK (status IN
  ('checked_in', 'waiting', 'vitals_pending', 'vitals_done', 'in_consult', 'checkout_pending', 'completed', 'no_show'));
