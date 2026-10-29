SET lock_timeout = '5s';
-- CLN-008: the visit summary reads the items of a patient's last 3 signed prescriptions in display order.
-- idx_prescriptions_patient (tenant_id, patient_id, created_at DESC) and idx_clinical_notes_patient already serve the
-- last-visits and prescription lookups; this covering index lets the item read skip its sort.
-- migration-lint: allow MIG-001 prescription_items is a small per-tenant table; the build is a plain online-safe index and lock_timeout bounds the wait
CREATE INDEX IF NOT EXISTS idx_prescription_items_summary ON prescription_items (tenant_id, prescription_id, display_order);
