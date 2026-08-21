-- E11 Clinical Workspace (remaining buildable slice) + E12 Specialty Workspaces (framework proof)
-- Refs: NB-106 (vitals), NB-107/108 (allergy register + hard-warning), NB-109/105 (prescriptions +
-- previous-meds view), NB-115 (diagnosis + encounter timeline — timeline itself is a query over
-- existing tables, no new table needed for it), NB-121 (specialty entitlement, reusing the existing
-- RBAC module-grant mechanism rather than a new feature-flag service — NB-008 doesn't exist: gating
-- the Dental tab behind a "specialty_dental" role grant is the whole framework, no staff-level
-- specialty tagging needed to prove it), NB-122/NB-127 (dental chart + per-tooth view, the one
-- specialty built as the framework's proof).

-- NB-107/108: severity drives the drawer's allergy banner styling; hard-warning is enforced in the
-- prescription service by name-matching a new drug against active substances, not a drug-interaction
-- database this app has no source for.
CREATE TABLE patient_allergies (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  patient_id   uuid NOT NULL REFERENCES patients(id),
  substance    text NOT NULL,
  severity     text NOT NULL CHECK (severity IN ('mild', 'moderate', 'severe')),
  reaction     text,
  active       boolean NOT NULL DEFAULT true,
  recorded_by  uuid NOT NULL REFERENCES staff(id),
  recorded_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_patient_allergies_patient ON patient_allergies (tenant_id, patient_id) WHERE active;
ALTER TABLE patient_allergies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON patient_allergies USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-106: one row per visit, same anchor as clinical_notes. Capturing vitals is also what advances
-- the queue from vitals_pending to vitals_done — see VitalsService.
CREATE TABLE vitals (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  queue_entry_id  uuid NOT NULL UNIQUE REFERENCES queue_entries(id),
  patient_id      uuid NOT NULL REFERENCES patients(id),
  height_cm       numeric(5,2),
  weight_kg       numeric(5,2),
  bp_systolic     integer,
  bp_diastolic    integer,
  pulse_bpm       integer,
  temp_celsius    numeric(4,1),
  spo2_percent    integer,
  recorded_by     uuid NOT NULL REFERENCES staff(id),
  recorded_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE vitals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vitals USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-109/105: free-text drug entry, not a coded formulary (NB-010's reference data service doesn't
-- exist). "Previous medicines" (NB-105) is a read over this table, not a separate one.
CREATE TABLE prescriptions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  queue_entry_id  uuid NOT NULL UNIQUE REFERENCES queue_entries(id),
  patient_id      uuid NOT NULL REFERENCES patients(id),
  doctor_id       uuid NOT NULL REFERENCES staff(id),
  status          text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'signed')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  signed_at       timestamptz
);
ALTER TABLE prescriptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prescriptions USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX idx_prescriptions_patient ON prescriptions (tenant_id, patient_id, created_at DESC);

CREATE TABLE prescription_items (
  id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                uuid NOT NULL REFERENCES tenants(id),
  prescription_id          uuid NOT NULL REFERENCES prescriptions(id) ON DELETE CASCADE,
  drug_name                text NOT NULL,
  dosage                   text,
  frequency                text,
  duration                 text,
  instructions             text,
  allergy_override_reason  text,
  display_order            integer NOT NULL DEFAULT 0
);
ALTER TABLE prescription_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prescription_items USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- NB-115: a named diagnosis alongside the note's free-text assessment — still free-text (no ICD
-- coding, same NB-010 gap), but distinct from "assessment" (clinical reasoning) as its own field.
ALTER TABLE clinical_notes ADD COLUMN IF NOT EXISTS diagnosis text;

-- NB-122/NB-127: FDI two-digit tooth numbering (11-48), one row per tooth per patient.
CREATE TABLE dental_chart_entries (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  patient_id     uuid NOT NULL REFERENCES patients(id),
  tooth_number   integer NOT NULL CHECK (tooth_number BETWEEN 11 AND 48),
  status         text NOT NULL DEFAULT 'healthy'
                   CHECK (status IN ('healthy', 'decayed', 'filled', 'missing', 'crown', 'root_canal')),
  note           text,
  updated_by     uuid NOT NULL REFERENCES staff(id),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, patient_id, tooth_number)
);
ALTER TABLE dental_chart_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dental_chart_entries USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
