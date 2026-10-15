-- CGV-002: outcome of the age-18 guardian review. Set when staff choose "keep proxy"; the patient then
-- leaves the guardian-reviews-due worklist. Cleared whenever guardian_id changes (a new guardian is a new review).
ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_review_kept_at timestamptz;
ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_review_kept_by uuid;
