SET lock_timeout = '5s';
-- FIN-015: per-doctor consultation fee (a blank override = no row = the head's base amount) and the follow-up window policy.
-- Plain tenant-scoped FK to the head (brand visibility is gone since V2521). Money is numeric(12,2) like charge_catalogue.
CREATE TABLE charge_doctor_fees (
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  charge_id  uuid NOT NULL REFERENCES charge_catalogue(id) ON DELETE CASCADE,
  doctor_id  uuid NOT NULL REFERENCES staff(id),
  amount     numeric(12,2) NOT NULL CHECK (amount >= 0),
  updated_by uuid REFERENCES staff(id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, charge_id, doctor_id)
);
SELECT enforce_tenant_rls('charge_doctor_fees');

-- Same key list as V2619 plus follow_up_window_days (default 7, FRD CC-11), seeded for every existing tenant.
ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes',
  'cash_variance_tolerance',
  'refund_owner_threshold',
  'follow_up_window_days',
  'print.default_paper.token_slip',
  'print.default_paper.rx',
  'print.default_paper.document',
  'print.default_paper.invoice',
  'print.default_paper.receipt'
)) NOT VALID;
ALTER TABLE clinic_policies VALIDATE CONSTRAINT clinic_policies_policy_key_check;
-- RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way V2500 does.
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (t.id, 'follow_up_window_days', '7')
      ON CONFLICT (tenant_id, policy_key) DO NOTHING;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
