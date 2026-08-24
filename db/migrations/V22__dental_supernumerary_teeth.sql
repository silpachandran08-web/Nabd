-- NB-122: a supernumerary tooth sits "near" an existing FDI position without replacing it, so the old
-- one-row-per-tooth-number uniqueness only holds for the standard (non-supernumerary) row anymore.
ALTER TABLE dental_chart_entries ADD COLUMN is_supernumerary boolean NOT NULL DEFAULT false;
ALTER TABLE dental_chart_entries DROP CONSTRAINT dental_chart_entries_tenant_id_patient_id_tooth_number_key;
CREATE UNIQUE INDEX dental_chart_entries_tooth_uniq
  ON dental_chart_entries (tenant_id, patient_id, tooth_number)
  WHERE NOT is_supernumerary;

-- NB-107 (severity-scaled prescription warnings) and NB-108 (audited overrides) reuse the existing
-- prescriptions/patient_allergies tables and audit_log respectively — no schema change for either.
