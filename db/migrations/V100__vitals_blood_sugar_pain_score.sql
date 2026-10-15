-- NUR-021: blood sugar and pain score on the nurse vitals form. Additive and nullable; the vitals table already has
-- RLS (V21) and column additions inherit its table grants, so nothing else is needed here.
-- blood_sugar is stored as entered; the display unit (mg/dL) is a UI constant and is not stored. It needs clinical sign-off.
-- pain_score is the 0-10 numeric rating scale; the CHECK mirrors the API validation.
ALTER TABLE vitals ADD COLUMN IF NOT EXISTS blood_sugar numeric(6,2);
ALTER TABLE vitals ADD COLUMN IF NOT EXISTS pain_score smallint;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'vitals_pain_score_range') THEN
    ALTER TABLE vitals ADD CONSTRAINT vitals_pain_score_range CHECK (pain_score IS NULL OR pain_score BETWEEN 0 AND 10);
  END IF;
END $$;
