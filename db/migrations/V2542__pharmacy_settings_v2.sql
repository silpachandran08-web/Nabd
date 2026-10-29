SET lock_timeout = '5s';
-- PHL-004: who changed the mode and when; licence numbers and the people the pharmacy depends on.
ALTER TABLE pharmacy_settings
  ADD COLUMN mode_changed_at timestamptz,
  ADD COLUMN mode_changed_by uuid REFERENCES staff (id),
  ADD COLUMN drug_licence_numbers jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN pharmacist_in_charge_staff_id uuid REFERENCES staff (id),
  ADD COLUMN alert_recipient_staff_id uuid REFERENCES staff (id);
