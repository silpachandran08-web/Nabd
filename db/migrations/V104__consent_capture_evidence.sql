-- CGV-005: who recorded a consent and through which channel. Nullable: older rows and the PATCH guardian flow carry no evidence.
-- consents keeps the RLS policy from V5/V6 and its existing grants (columns only).
ALTER TABLE consents
  ADD COLUMN IF NOT EXISTS captured_by uuid REFERENCES staff(id),
  ADD COLUMN IF NOT EXISTS capture_channel text;
ALTER TABLE consents DROP CONSTRAINT IF EXISTS consents_capture_channel_check;
ALTER TABLE consents ADD CONSTRAINT consents_capture_channel_check
  CHECK (capture_channel IS NULL OR capture_channel IN ('in_person', 'phone', 'written'));
