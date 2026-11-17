SET lock_timeout = '5s';
-- COM-025: what a patient wants to receive and in which language, per patient per clinic. No row = the defaults (clinic language,
-- WhatsApp, every category on, no extra quiet hours, no caregivers). categories: {"careReminders","recalls","feedback","reviews"} ->
-- boolean. caregiver_recipients: [{"personRef": <caregiver patient id>, "categories": [...]}], only caregivers on the patient's
-- logistics recipient list (IDC-014 CareRecipients) are accepted, and the send path re-checks them. version grows on every change
-- (patient.notification_preferences.changed.v1). updated_by: the staff id, or 'whatsapp' when the patient changed it in chat.
CREATE TABLE patient_notification_preferences (
  tenant_id            uuid NOT NULL REFERENCES tenants (id),
  patient_id           uuid NOT NULL,
  language             text CHECK (language IN ('en', 'hi', 'ar', 'kn', 'te')),
  channel              text NOT NULL DEFAULT 'whatsapp' CHECK (channel IN ('whatsapp', 'sms', 'email')),
  categories           jsonb NOT NULL DEFAULT '{}'::jsonb,
  quiet_start          time,
  quiet_end            time,
  caregiver_recipients jsonb NOT NULL DEFAULT '[]'::jsonb,
  version              bigint NOT NULL DEFAULT 1,
  updated_by           text,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, patient_id),
  CONSTRAINT patient_notification_preferences_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT patient_notification_preferences_quiet_pair CHECK ((quiet_start IS NULL) = (quiet_end IS NULL))
);
SELECT enforce_tenant_rls('patient_notification_preferences');

-- COM-025: a caregiver's copy of a patient message (dual delivery) names the patient it is about, so the send-time check can ask
-- whether the caregiver is still authorised and still chosen. NULL for every other request. Nullable, no default: metadata only.
ALTER TABLE notification_requests ADD COLUMN about_patient_id uuid;
