-- V55 (DOC-N1): optional professional registration number for staff (e.g. a medical council / SCFHS number),
-- printed in the header of a signed prescription. Additive and nullable: existing rows are untouched, and a
-- null means "not set" (the print view says so rather than inventing a number). The staff table already has
-- tenant row-level security; a new column on it is covered by the same policy.
-- Written to be safe to re-run (IF NOT EXISTS). The charset is letters, digits, space and . / - only, 1 to 40
-- characters, starting with a letter or digit, so it cannot carry markup or control characters.
ALTER TABLE staff
  ADD COLUMN IF NOT EXISTS professional_registration_no text
    CONSTRAINT chk_staff_professional_registration_no
    CHECK (professional_registration_no IS NULL
           OR professional_registration_no ~ '^[A-Za-z0-9][A-Za-z0-9 ./-]{0,39}$');
