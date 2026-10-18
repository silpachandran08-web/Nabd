-- PLT-018 (MF-04, ADR-07, CONS M1): contain brand-visibility widening.
-- V6/V17/V19 gave 20 tables an OR-shaped USING policy with no WITH CHECK: reads widened by
-- brands.data_visibility_scope, writes into sibling clinics were allowed, and the OR defeated indexes.
-- Replaced per command: SELECT reads app.visible_tenant_ids (indexable "= ANY(uuid[])"), INSERT/UPDATE
-- WITH CHECK pin the row to the caller's own app.tenant_id, DELETE is own-tenant only.
-- roles/staff/sessions are restored to the single-tenant form unconditionally (no flag widens them).
-- NULLIF(...,''): a pooled connection's GUC reverts to '' (not NULL) after a transaction; ''::uuid throws.
-- FORCE ROW LEVEL SECURITY is OPS-003's migration (applies after this one), not repeated here.

DROP POLICY tenant_isolation ON roles;
CREATE POLICY tenant_isolation ON roles USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON staff;
CREATE POLICY tenant_isolation ON staff USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON sessions;
CREATE POLICY tenant_isolation ON sessions USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON patients;
CREATE POLICY tenant_read ON patients FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON patients FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON patients FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON patients FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON doctor_working_hours;
CREATE POLICY tenant_read ON doctor_working_hours FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON doctor_working_hours FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON doctor_working_hours FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON doctor_working_hours FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON doctor_leave;
CREATE POLICY tenant_read ON doctor_leave FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON doctor_leave FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON doctor_leave FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON doctor_leave FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON appointments;
CREATE POLICY tenant_read ON appointments FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON appointments FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON appointments FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON appointments FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON queue_entries;
CREATE POLICY tenant_read ON queue_entries FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON queue_entries FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON queue_entries FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON queue_entries FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON consents;
CREATE POLICY tenant_read ON consents FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON consents FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON consents FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON consents FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON patient_merges;
CREATE POLICY tenant_read ON patient_merges FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON patient_merges FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON patient_merges FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON patient_merges FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON clinic_setup_progress;
CREATE POLICY tenant_read ON clinic_setup_progress FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON clinic_setup_progress FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON clinic_setup_progress FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON clinic_setup_progress FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON charge_catalogue;
CREATE POLICY tenant_read ON charge_catalogue FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON charge_catalogue FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON charge_catalogue FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON charge_catalogue FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON clinic_policies;
CREATE POLICY tenant_read ON clinic_policies FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON clinic_policies FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON clinic_policies FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON clinic_policies FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON clinic_holidays;
CREATE POLICY tenant_read ON clinic_holidays FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON clinic_holidays FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON clinic_holidays FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON clinic_holidays FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON staff_shifts;
CREATE POLICY tenant_read ON staff_shifts FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON staff_shifts FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON staff_shifts FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON staff_shifts FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON attendance_records;
CREATE POLICY tenant_read ON attendance_records FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON attendance_records FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON attendance_records FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON attendance_records FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON licence_registry;
CREATE POLICY tenant_read ON licence_registry FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON licence_registry FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON licence_registry FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON licence_registry FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON data_import_jobs;
CREATE POLICY tenant_read ON data_import_jobs FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON data_import_jobs FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON data_import_jobs FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON data_import_jobs FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON data_export_jobs;
CREATE POLICY tenant_read ON data_export_jobs FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON data_export_jobs FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON data_export_jobs FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON data_export_jobs FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DROP POLICY tenant_isolation ON clinical_notes;
CREATE POLICY tenant_read ON clinical_notes FOR SELECT USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[]));
CREATE POLICY tenant_insert ON clinical_notes FOR INSERT WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_update ON clinical_notes FOR UPDATE USING (tenant_id = ANY (string_to_array(NULLIF(current_setting('app.visible_tenant_ids', true), ''), ',')::uuid[])) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_delete ON clinical_notes FOR DELETE USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- nextTokenNumber / countSessionOccupancy now filter on tenant_id; queue_entries already has
-- idx_queue_doctor_date (tenant_id, doctor_id, queue_date) (V4) and a UNIQUE on the same prefix.
-- appointments only had (doctor_id, start_time), so add the tenant-led twin.
-- shortcut: plain CREATE INDEX (Flyway runs this in a transaction, so no CONCURRENTLY); on a large prod
-- table run it by hand with CONCURRENTLY first, IF NOT EXISTS makes this a no-op afterwards.
CREATE INDEX IF NOT EXISTS idx_appointments_tenant_doctor_start ON appointments (tenant_id, doctor_id, start_time);
