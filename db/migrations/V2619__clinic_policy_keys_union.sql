SET lock_timeout = '5s';
-- Merge fix: V2490 (FEF-046 print.default_paper.*) and V2520 (FIN-013 refund_owner_threshold) each rewrote
-- clinic_policies_policy_key_check from the V1530 list, so V2520 dropped the print keys. Union of both.
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
  'print.default_paper.token_slip',
  'print.default_paper.rx',
  'print.default_paper.document',
  'print.default_paper.invoice',
  'print.default_paper.receipt'
)) NOT VALID;
ALTER TABLE clinic_policies VALIDATE CONSTRAINT clinic_policies_policy_key_check;
