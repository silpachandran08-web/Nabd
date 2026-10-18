-- PLT-012: break-glass becomes a gated, read-only, 30-minute emergency access (was: any caller got the
-- Owner's full grants). Columns for the justified activation, the elevated session and the revoker.
ALTER TABLE break_glass_grants
  ADD COLUMN reason_code text NOT NULL DEFAULT 'other'
    CHECK (reason_code IN ('unconscious_patient','life_threatening','no_treating_clinician_available','other')),
  ADD COLUMN scope text NOT NULL DEFAULT 'clinical_read' CHECK (scope = 'clinical_read'),
  ADD COLUMN patient_id uuid,
  ADD COLUMN step_up_verified boolean NOT NULL DEFAULT false,
  ADD COLUMN notified_at timestamptz,
  ADD COLUMN session_id uuid,
  ADD COLUMN deactivated_by uuid REFERENCES staff(id);

ALTER TABLE break_glass_grants DROP CONSTRAINT IF EXISTS break_glass_grants_deactivated_reason_check;
ALTER TABLE break_glass_grants ADD CONSTRAINT break_glass_grants_deactivated_reason_check
  CHECK (deactivated_reason IN ('expired','manual','revoked_by_owner'));

-- one active grant per staff member (the 409 ALREADY_ACTIVE guard, race-proof)
CREATE UNIQUE INDEX uq_break_glass_one_active ON break_glass_grants (staff_id) WHERE deactivated_at IS NULL;

-- per-command policies with WITH CHECK, forced
DROP POLICY tenant_isolation ON break_glass_grants;
CREATE POLICY bg_select ON break_glass_grants FOR SELECT USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bg_insert ON break_glass_grants FOR INSERT WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bg_update ON break_glass_grants FOR UPDATE USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY bg_delete ON break_glass_grants FOR DELETE USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
ALTER TABLE break_glass_grants FORCE ROW LEVEL SECURITY;

-- clinical:breakGlass only for clinician presets: the built-in Owner role and roles named Doctor / Owner-doctor / ER.
-- Receptionist, Nurse, Billing and every other role stay without it.
UPDATE roles
SET grants = (SELECT jsonb_agg(CASE WHEN e->>'module' = 'clinical' THEN e || '{"breakGlass":true}'::jsonb ELSE e END)
              FROM jsonb_array_elements(grants) e)
WHERE grants @> '[{"module":"clinical"}]'::jsonb
  AND (built_in OR lower(name) IN ('doctor','owner-doctor','owner doctor','er','er doctor','emergency doctor'));
