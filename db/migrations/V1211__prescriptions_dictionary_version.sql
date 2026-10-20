SET lock_timeout = '5s';
-- CLN-004: the safety dictionary version (e.g. 'IN v0') that was active when the prescription was signed. Written by
-- PrescriptionService.sign() in the same UPDATE that flips draft -> signed (V51's trigger then freezes it). NULL for
-- drafts and for prescriptions signed before this migration (not backfilled: unknown, not invented).
ALTER TABLE prescriptions ADD COLUMN IF NOT EXISTS safety_dictionary_version text;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'prescriptions_dictionary_version_signed' AND conrelid = 'prescriptions'::regclass) THEN
    ALTER TABLE prescriptions ADD CONSTRAINT prescriptions_dictionary_version_signed
      CHECK (safety_dictionary_version IS NULL OR status = 'signed') NOT VALID;
  END IF;
END $$;
ALTER TABLE prescriptions VALIDATE CONSTRAINT prescriptions_dictionary_version_signed;
