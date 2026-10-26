SET lock_timeout = '5s';
-- FEF-046: per-document default paper (a4 | a5 | 80mm), validated in SetupService. Not seeded: an absent row means the
-- built-in default of the print kit; the first PATCH inserts it. Keeps every key allowed by V1530.
ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes',
  'cash_variance_tolerance',
  'print.default_paper.token_slip',
  'print.default_paper.rx',
  'print.default_paper.document',
  'print.default_paper.invoice',
  'print.default_paper.receipt'
)) NOT VALID;
ALTER TABLE clinic_policies VALIDATE CONSTRAINT clinic_policies_policy_key_check;
