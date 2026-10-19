-- FEF-005: widen the clinic_policies key CHECK for the per-tenant idle-lock policy (2-30 minutes, validated in SetupService).
-- Not seeded per tenant: absent row = the shell's built-in default; the first PATCH inserts it.
ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes'
));
