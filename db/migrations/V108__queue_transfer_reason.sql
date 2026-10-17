-- REC-021: why a patient was moved to another department. Stored on the new (transfer) leg.
-- Expand-only; queue_entries already has RLS + grants, new columns inherit them.
ALTER TABLE queue_entries
  ADD COLUMN IF NOT EXISTS transfer_reason text
    CHECK (transfer_reason IN ('wrong_department','doctor_unavailable','patient_request','other')),
  ADD COLUMN IF NOT EXISTS transfer_reason_note text
    CHECK (char_length(transfer_reason_note) <= 200);
