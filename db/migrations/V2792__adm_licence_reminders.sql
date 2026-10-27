SET lock_timeout = '5s';
-- ADM-032: the licence register covers nurses, lab staff and pharmacists as well as clinicians and facilities, keeps which reminder
-- thresholds (60/30/7 days) were already sent, the owner's time-boxed override of a lapsed clinician licence and the certificate
-- (a file_objects id in the regional object store). Region already accepts IN/KSA/SA/AE/QA (V1991). RLS on licence_registry is already
-- enabled and forced with per-command policies (V211, V2521). staff.bookable_online is the flag online booking reads; only the licence
-- lifecycle writes it, through iam.api.StaffLicenceSync.
ALTER TABLE licence_registry DROP CONSTRAINT licence_registry_licence_type_check;
ALTER TABLE licence_registry ADD CONSTRAINT licence_registry_licence_type_check
  CHECK (licence_type IN ('clinician', 'facility', 'nurse', 'lab', 'pharmacist')) NOT VALID;
ALTER TABLE licence_registry VALIDATE CONSTRAINT licence_registry_licence_type_check;

ALTER TABLE licence_registry
  ADD COLUMN certificate_upload_id uuid,
  ADD COLUMN reminders_sent jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN override_until date,
  ADD COLUMN override_reason text,
  ADD COLUMN override_by uuid;
ALTER TABLE licence_registry ADD CONSTRAINT licence_registry_override_check
  CHECK (override_until IS NULL OR (override_reason IS NOT NULL AND length(override_reason) BETWEEN 3 AND 200)) NOT VALID;
ALTER TABLE licence_registry VALIDATE CONSTRAINT licence_registry_override_check;
-- migration-lint: allow MIG-001 the register holds a few rows per clinic; Flyway cannot run CONCURRENTLY inside its transaction
CREATE INDEX idx_licence_registry_expiry ON licence_registry (tenant_id, expiry_date);

ALTER TABLE staff ADD COLUMN bookable_online boolean NOT NULL DEFAULT true;
