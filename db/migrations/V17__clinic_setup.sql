-- E07 Clinic Setup & Administration
-- Refs: NB-060 (wizard), NB-061 (charge master), NB-062 (policies),
-- NB-063 (consent contact), NB-064 (schedule/holidays), NB-065 (shifts/attendance),
-- NB-066 (payroll export), NB-067 (subscription), NB-068 (import/export),
-- NB-069 (setup checklist), NB-070 (licence registry).

-- Tenant-level clinic profile fields that the setup wizard and hub edit.
ALTER TABLE tenants
  ADD COLUMN IF NOT EXISTS timezone text DEFAULT 'UTC',
  ADD COLUMN IF NOT EXISTS tax_id text,
  ADD COLUMN IF NOT EXISTS tax_id_type text CHECK (tax_id_type IN ('GSTIN', 'VAT')),
  ADD COLUMN IF NOT EXISTS whatsapp_number text,
  ADD COLUMN IF NOT EXISTS setup_completed_at timestamptz,
  ADD COLUMN IF NOT EXISTS specialties text[] DEFAULT '{}',  -- e.g. {'Dermatology','Dental'}
  ADD COLUMN IF NOT EXISTS consent_contact_name text,
  ADD COLUMN IF NOT EXISTS consent_contact_email text,
  ADD COLUMN IF NOT EXISTS consent_contact_phone text;

-- ── Wizard progress: skippable, resumable checklist ──
CREATE TABLE clinic_setup_progress (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  step        text NOT NULL CHECK (step IN (
    'welcome','profile','tax','doctors','schedule','charges','pharmacy',
    'whatsapp','go_live'
  )),
  status      text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','skipped','done')),
  skipped_at  timestamptz,
  done_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, step)
);
CREATE TRIGGER trg_clinic_setup_progress_updated_at BEFORE UPDATE ON clinic_setup_progress
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE clinic_setup_progress ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clinic_setup_progress USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);

-- ── Charge head / price master (NB-061) ──
CREATE TABLE charge_catalogue (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  code               text NOT NULL,          -- internal service code
  name               text NOT NULL,          -- display label / button text
  category           text NOT NULL,          -- e.g. Consultation, Procedure, Lab
  base_amount        numeric(12,2) NOT NULL,
  follow_up_amount   numeric(12,2),
  emergency_amount   numeric(12,2),
  tax_code           text,                   -- GST HSN/SAC or VAT category
  doctor_override    boolean NOT NULL DEFAULT false,
  active             boolean NOT NULL DEFAULT true,
  effective_from     date NOT NULL DEFAULT CURRENT_DATE,
  effective_to       date,
  display_order      int NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, code)
);
CREATE TRIGGER trg_charge_catalogue_updated_at BEFORE UPDATE ON charge_catalogue
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE charge_catalogue ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charge_catalogue USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
CREATE INDEX idx_charge_catalogue_tenant_active ON charge_catalogue (tenant_id, active, display_order);

-- ── Simple clinic policies (NB-062) ──
CREATE TABLE clinic_policies (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  policy_key  text NOT NULL CHECK (policy_key IN (
    'cancellation_window_hours',
    'no_show_fee_amount',
    'reminder_hours_before',
    'refund_rule',
    'appointment_buffer_minutes'
  )),
  value       text NOT NULL,
  version     int NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, policy_key)
);
CREATE TRIGGER trg_clinic_policies_updated_at BEFORE UPDATE ON clinic_policies
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE clinic_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clinic_policies USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);

-- ── Clinic-wide holidays (NB-064) ──
CREATE TABLE clinic_holidays (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  holiday_date date NOT NULL,
  name         text NOT NULL,
  recurring    boolean NOT NULL DEFAULT false,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, holiday_date)
);
CREATE TRIGGER trg_clinic_holidays_updated_at BEFORE UPDATE ON clinic_holidays
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE clinic_holidays ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clinic_holidays USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);

-- ── Staff shifts (NB-065) ──
CREATE TABLE staff_shifts (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  staff_id       uuid NOT NULL REFERENCES staff(id),
  pattern_json   jsonb NOT NULL DEFAULT '{}',  -- e.g. {"mon":[{"start":"09:00","end":"14:00"},{"start":"17:00","end":"21:00"}]}
  effective_from date NOT NULL DEFAULT CURRENT_DATE,
  effective_to   date,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_staff_shifts_updated_at BEFORE UPDATE ON staff_shifts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE staff_shifts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON staff_shifts USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
CREATE INDEX idx_staff_shifts_staff ON staff_shifts (tenant_id, staff_id, effective_from);

-- ── Attendance records (NB-065 / NB-066) ──
CREATE TABLE attendance_records (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  staff_id    uuid NOT NULL REFERENCES staff(id),
  record_date date NOT NULL,
  check_in    timestamptz,
  check_out   timestamptz,
  notes       text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, staff_id, record_date)
);
CREATE TRIGGER trg_attendance_records_updated_at BEFORE UPDATE ON attendance_records
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance_records USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
CREATE INDEX idx_attendance_records_month ON attendance_records (tenant_id, staff_id, record_date);

-- ── Licence registry (NB-070) ──
CREATE TABLE licence_registry (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  licence_type   text NOT NULL CHECK (licence_type IN ('clinician','facility')),
  holder_id      uuid REFERENCES staff(id),  -- null for facility licence
  holder_name    text,                        -- denormalised for facility name / clinician name
  number         text NOT NULL,
  issuing_body   text,
  expiry_date    date,
  region         text NOT NULL CHECK (region IN ('IN','KSA')),
  status         text NOT NULL DEFAULT 'valid' CHECK (status IN ('valid','expiring_soon','expired')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_licence_registry_updated_at BEFORE UPDATE ON licence_registry
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE licence_registry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON licence_registry USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);
CREATE INDEX idx_licence_registry_tenant ON licence_registry (tenant_id, licence_type);

-- ── Data import jobs (NB-068) ──
CREATE TABLE data_import_jobs (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  import_type    text NOT NULL CHECK (import_type IN ('patients','appointments','invoices','charges')),
  file_name      text NOT NULL,
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','validating','importing','done','failed')),
  result_url     text,
  error_message  text,
  requested_by   uuid NOT NULL REFERENCES staff(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_data_import_jobs_updated_at BEFORE UPDATE ON data_import_jobs
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE data_import_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON data_import_jobs USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);

-- ── Data export jobs (NB-068) ──
CREATE TABLE data_export_jobs (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  export_type    text NOT NULL CHECK (export_type IN ('full_tenant','patients','invoices','charges')),
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','done','failed')),
  result_url     text,
  error_message  text,
  requested_by   uuid NOT NULL REFERENCES staff(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_data_export_jobs_updated_at BEFORE UPDATE ON data_export_jobs
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE data_export_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON data_export_jobs USING (
  tenant_id = current_setting('app.tenant_id', true)::uuid
  OR tenant_id::text = ANY(string_to_array(NULLIF(current_setting('app.accessible_tenant_ids', true), ''), ','))
);

-- Default policy seeds per region. These are inserted for new tenants by the application;
-- existing tenants get them lazily on first read if missing.
-- (No INSERT here to avoid touching existing data during migration.)
