SET lock_timeout = '5s';
-- ACC-018: how waiting time is shown to patients. Doctor level here; session level on the working-hours block; the facility
-- default is facility_queue_settings (one row per clinic; not clinic_policies, whose key CHECK is rewritten by several stories), 'range' when unset. NULL = inherit. No PHI.
CREATE TABLE doctor_queue_settings (
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  doctor_id        uuid PRIMARY KEY REFERENCES staff(id),
  eta_display_mode text CHECK (eta_display_mode IN ('exact','range','position','delay_only','hidden')),
  updated_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('doctor_queue_settings');

CREATE TABLE facility_queue_settings (
  tenant_id        uuid PRIMARY KEY REFERENCES tenants(id),
  eta_display_mode text NOT NULL CHECK (eta_display_mode IN ('exact','range','position','delay_only','hidden')),
  updated_by       uuid,
  updated_at       timestamptz NOT NULL DEFAULT now()
);
SELECT enforce_tenant_rls('facility_queue_settings');

ALTER TABLE doctor_working_hours ADD COLUMN eta_display_mode text
  CHECK (eta_display_mode IN ('exact','range','position','delay_only','hidden'));
