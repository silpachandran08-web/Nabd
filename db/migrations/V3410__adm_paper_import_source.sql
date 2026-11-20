SET lock_timeout = '5s';
-- ADM-014: paper cards typed in at the desk. registration_source gains 'paper_import'; the card carries only a year of birth, so the
-- stored date of birth is 1 January of that year and dob_year_only says so until staff enter the full date. paper_note is the card's
-- free text (last visit and doctor). Constant default and nullable column: no table rewrite.
ALTER TABLE patients DROP CONSTRAINT patients_registration_source_chk;
ALTER TABLE patients ADD CONSTRAINT patients_registration_source_chk CHECK (registration_source IN ('desk','whatsapp','web','app','paper_import')) NOT VALID;
ALTER TABLE patients VALIDATE CONSTRAINT patients_registration_source_chk;
ALTER TABLE patients ADD COLUMN dob_year_only boolean NOT NULL DEFAULT false;
ALTER TABLE patients ADD COLUMN paper_note text;
