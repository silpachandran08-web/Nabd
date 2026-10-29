-- OPS-005.1: run BEFORE db/migrations/V2602 (VALIDATE) on a database that has data, as a superuser (RLS must not hide rows):
--   psql -v ON_ERROR_STOP=1 -d nabd -f scripts/db/ops005-cross-tenant-precheck.sql
-- Finds every row whose patient/doctor/staff/department belongs to ANOTHER tenant, records it in cross_tenant_ref_quarantine
-- (table, column, row id and the two tenants; no clinical values) and fails when any exist. Zero rows = safe to validate.
-- Quarantined rows are not deleted: the clinic's DPO/support decides whether to re-point or retire them, then re-runs this script.
DO $$
DECLARE r record; found bigint;
BEGIN
  FOR r IN SELECT * FROM (VALUES
    ('appointments', 'patient_id', 'patients'),
    ('queue_entries', 'patient_id', 'patients'),
    ('prescriptions', 'patient_id', 'patients'),
    ('invoices', 'patient_id', 'patients'),
    ('clinical_notes', 'patient_id', 'patients'),
    ('vitals', 'patient_id', 'patients'),
    ('waitlist_entries', 'patient_id', 'patients'),
    ('patient_allergies', 'patient_id', 'patients'),
    ('chronic_conditions', 'patient_id', 'patients'),
    ('clinical_documents', 'patient_id', 'patients'),
    ('vaccine_records', 'patient_id', 'patients'),
    ('dental_chart_entries', 'patient_id', 'patients'),
    ('consents', 'patient_id', 'patients'),
    ('appointments', 'doctor_id', 'staff'),
    ('queue_entries', 'doctor_id', 'staff'),
    ('prescriptions', 'doctor_id', 'staff'),
    ('invoices', 'doctor_id', 'staff'),
    ('clinical_notes', 'doctor_id', 'staff'),
    ('waitlist_entries', 'doctor_id', 'staff'),
    ('clinical_documents', 'doctor_id', 'staff'),
    ('role_delegations', 'staff_id', 'staff'),
    ('role_delegations', 'granted_by', 'staff'),
    ('queue_entries', 'department_id', 'departments')
  ) AS v(child, col, parent) LOOP
    EXECUTE format('INSERT INTO cross_tenant_ref_quarantine (table_name, column_name, row_id, child_tenant_id, parent_tenant_id) '
                   'SELECT %L, %L, c.id, c.tenant_id, p.tenant_id FROM %I c JOIN %I p ON p.id = c.%I WHERE c.tenant_id <> p.tenant_id '
                   'ON CONFLICT DO NOTHING', r.child, r.col, r.child, r.parent, r.col);
    GET DIAGNOSTICS found = ROW_COUNT;
    IF found > 0 THEN RAISE NOTICE '% rows in %.% reference another tenant', found, r.child, r.col; END IF;
  END LOOP;
END $$;

-- A separate statement, so the quarantine rows above are committed before this one fails the run.
DO $$
DECLARE total bigint;
BEGIN
  SELECT count(*) INTO total FROM cross_tenant_ref_quarantine;
  IF total > 0 THEN
    RAISE EXCEPTION 'OPS-005 precheck: % cross-tenant reference(s) quarantined; resolve them before VALIDATE', total;
  END IF;
  RAISE NOTICE 'OPS-005 precheck: 0 cross-tenant references';
END $$;
