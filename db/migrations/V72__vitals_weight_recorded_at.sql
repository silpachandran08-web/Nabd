-- DOC-036: the weight-based dose calculator must tell the prescriber how old the weight it used is.
-- vitals.recorded_at moves on every save of the visit row (a later pulse-only correction would refresh it),
-- so it cannot say when the WEIGHT was measured. weight_recorded_at is set only when a save carries a weight.
-- Existing rows are deliberately NOT backfilled: their weight date is unknown and recorded_at may be later than the
-- weight. The dose calculator reports a null weightRecordedAt for them and the UI treats that as stale.
ALTER TABLE vitals ADD COLUMN IF NOT EXISTS weight_recorded_at timestamptz;
