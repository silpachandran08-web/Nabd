SET lock_timeout = '5s';
-- OPS-007 (DP-03/MF-07): one pre-computed row per patient for the Patient Registry, maintained by the
-- RegistryProjector (backend com.nabd.hms.patient.registry). registry_dirty is its work queue: triggers
-- (V221) add the patient to it in the writer's own transaction, so a commit can never lose a change.
-- Holds PHI (name_search, top_allergy): a plain table under FORCE RLS, never cached elsewhere.
CREATE SEQUENCE patient_registry_change_seq;

CREATE TABLE patient_registry_summary (
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  patient_id           uuid NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
  name_search          text,
  mrn                  text,
  phone_last10         text,
  last_visit_date      date,
  next_appt_at         timestamptz,
  next_doctor_id       uuid,
  balance_due          numeric(14,3) NOT NULL DEFAULT 0,
  has_active_condition boolean NOT NULL DEFAULT false,
  followup_due         date,
  top_allergy          text,
  pkg_used             int NOT NULL DEFAULT 0,
  pkg_total            int NOT NULL DEFAULT 0,
  duplicate_flag       boolean NOT NULL DEFAULT false,
  archived             boolean NOT NULL DEFAULT false,
  change_seq           bigint NOT NULL,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, patient_id)
);
CREATE INDEX idx_registry_summary_name ON patient_registry_summary (tenant_id, name_search, patient_id);
CREATE INDEX idx_registry_summary_followup ON patient_registry_summary (tenant_id, followup_due) WHERE followup_due IS NOT NULL;
CREATE INDEX idx_registry_summary_balance ON patient_registry_summary (tenant_id, balance_due) WHERE balance_due > 0;

CREATE TABLE registry_dirty (
  tenant_id  uuid NOT NULL,
  patient_id uuid NOT NULL,
  PRIMARY KEY (tenant_id, patient_id)
);

SELECT enforce_tenant_rls('patient_registry_summary');
SELECT enforce_tenant_rls('registry_dirty');
