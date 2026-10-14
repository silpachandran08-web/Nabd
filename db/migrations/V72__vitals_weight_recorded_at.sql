-- DOC-036: the weight-based dose calculator must tell the prescriber how old the weight it used is.
-- vitals.recorded_at moves on every save of the visit row (a later pulse-only correction would refresh it),
-- so it cannot say when the WEIGHT was measured. weight_recorded_at is set only when a save carries a weight.
-- Existing rows are backfilled from recorded_at (best available; may overstate freshness for rows whose
-- weight predates a later non-weight save, which is why the dose calculator shows the date itself).
ALTER TABLE vitals ADD COLUMN IF NOT EXISTS weight_recorded_at timestamptz;
UPDATE vitals SET weight_recorded_at = recorded_at WHERE weight_kg IS NOT NULL AND weight_recorded_at IS NULL;
