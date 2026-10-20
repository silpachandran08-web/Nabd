-- OPS-046.1: synthetic load-scale dataset (no real people, no real credentials). Re-runnable per tag.
--   psql "$PERF_DB_URL" -v ON_ERROR_STOP=1 -v tag=s2 -v tenants=50 -v patients=10000 -v pin_hash="$(java ... PinHash.java "$PERF_PIN")" -f seed.sql
-- 50 tenants x 10,000 = 500k patients. Each tenant: slug perf-<tag>-NN, 3 receptionists rcp1..3@perf-<tag>-NN.invalid,
-- 1 doctor doc@perf-<tag>-NN.invalid (all share the one synthetic PIN given at seed time), ~30% past visits, 5% open
-- balances, 10% chronic conditions with review dates, 1% duplicate phones, 1% archived, 48 of today's appointments.
-- Works as any role with DML rights (nabd_app included): rows are written per tenant under app.tenant_id, as RLS requires.
-- The registry read model fills through the normal path (triggers -> RegistryProjector); run.sh waits for it to drain.
\set ON_ERROR_STOP on
SET statement_timeout = 0; -- nabd_app's role default (5 s) is for API requests, not a bulk load
SELECT set_config('perf.tag', :'tag', false), set_config('perf.tenants', :'tenants', false),
       set_config('perf.patients', :'patients', false), set_config('perf.pin_hash', :'pin_hash', false) \g /dev/null

DO $$
DECLARE
  tag text := current_setting('perf.tag');
  n_tenants int := current_setting('perf.tenants')::int;
  n_patients int := current_setting('perf.patients')::int;
  pin text := current_setting('perf.pin_hash');
  tid uuid; rcp_role uuid; doc_role uuid; doc uuid; rcp uuid; keeper uuid; t_slug text;
  grant_rw text := '{"view":true,"create":true,"edit":true,"delete":false,"approve":false,"refundDiscount":false,"export":false,"breakGlass":false}';
  today date := (now() AT TIME ZONE 'Asia/Kolkata')::date;
BEGIN
  IF pin NOT LIKE '$argon2%' THEN RAISE EXCEPTION 'pin_hash must be an Argon2 hash (perf/data/PinHash.java)'; END IF;
  FOR k IN 1..n_tenants LOOP
    t_slug := format('perf-%s-%s', tag, lpad(k::text, 2, '0'));
    CONTINUE WHEN EXISTS (SELECT 1 FROM tenants WHERE tenants.slug = t_slug);
    tid := gen_random_uuid();
    INSERT INTO tenants (id, slug, name, region, status, timezone) VALUES (tid, t_slug, 'Perf Clinic ' || t_slug, 'IN', 'active', 'Asia/Kolkata');
    PERFORM set_config('app.tenant_id', tid::text, true);
    INSERT INTO departments (tenant_id, name, is_default) VALUES (tid, 'General', true);
    rcp_role := gen_random_uuid(); doc_role := gen_random_uuid();
    INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES
      (rcp_role, tid, 'Perf Receptionist', false, ('[' || concat_ws(',',
         '{"module":"patients",' || substr(grant_rw, 2), '{"module":"queue",' || substr(grant_rw, 2),
         '{"module":"billing","view":true,"create":false,"edit":false,"delete":false,"approve":false,"refundDiscount":false,"export":false,"breakGlass":false}') || ']')::jsonb),
      (doc_role, tid, 'Perf Doctor', false, ('[' || concat_ws(',',
         '{"module":"patients","view":true,"create":false,"edit":false,"delete":false,"approve":false,"refundDiscount":false,"export":false,"breakGlass":false}',
         '{"module":"queue",' || substr(grant_rw, 2), '{"module":"clinical",' || substr(grant_rw, 2)) || ']')::jsonb);
    doc := gen_random_uuid();
    INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, email_verified, mobile_verified, mfa_enabled)
      VALUES (doc, tid, doc_role, 'doc@' || t_slug || '.invalid', 'Perf Doctor', '+91700' || lpad((k * 10)::text, 7, '0'), pin, 'active', true, true, false);
    FOR r IN 1..3 LOOP
      rcp := gen_random_uuid();
      INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, email_verified, mobile_verified, mfa_enabled)
        VALUES (rcp, tid, rcp_role, format('rcp%s@%s.invalid', r, t_slug), 'Perf Reception ' || r, '+91700' || lpad((k * 10 + r)::text, 7, '0'), pin, 'active', true, true, false);
    END LOOP;
    keeper := gen_random_uuid();
    INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (keeper, tid, 'Perf Keeper', '+916999999999', '1960-01-01', 'male');
    INSERT INTO patients (tenant_id, name, phone, dob, gender)
      SELECT tid, initcap(chr(97 + (random() * 25)::int) || substr(md5(g::text || t_slug), 1, 6) || ' ' || substr(md5((g * 7)::text), 1, 8)),
             '+916' || lpad((CASE WHEN g % 100 = 0 THEN g - 1 ELSE g END)::text, 9, '0'),
             date '1940-01-01' + (random() * 30000)::int, CASE WHEN g % 2 = 0 THEN 'male' ELSE 'female' END
      FROM generate_series(1, n_patients - 1) g;
    INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status)
      SELECT tid, id, doc, (SELECT id FROM departments WHERE tenant_id = tid AND is_default), d, row_number() OVER (PARTITION BY d ORDER BY id), 'completed'
      FROM (SELECT id, today - 1 - (random() * 300)::int AS d FROM patients WHERE tenant_id = tid AND random() < 0.3) v;
    INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by)
      SELECT tid, id, 400, 0, 400, rcp FROM patients WHERE tenant_id = tid AND random() < 0.05;
    INSERT INTO chronic_conditions (tenant_id, patient_id, condition, status, review_due_date, recorded_by)
      SELECT tid, id, 'Synthetic condition', 'active', today + (random() * 60 - 30)::int, doc FROM patients WHERE tenant_id = tid AND random() < 0.1;
    INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time)
      SELECT tid, p.id, doc, ((today + time '09:00') + p.s * interval '15 minutes') AT TIME ZONE 'Asia/Kolkata',
             ((today + time '09:15') + p.s * interval '15 minutes') AT TIME ZONE 'Asia/Kolkata'
      FROM (SELECT id, row_number() OVER (ORDER BY id) - 1 AS s FROM patients WHERE tenant_id = tid AND id <> keeper LIMIT 48) p;
    UPDATE patients SET status = 'merged', merged_into_id = keeper WHERE tenant_id = tid AND id <> keeper AND random() < 0.01
      AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.patient_id = patients.id);
  END LOOP;
END $$;

SELECT count(*) AS perf_tenants FROM tenants WHERE slug LIKE 'perf-' || :'tag' || '-%';
