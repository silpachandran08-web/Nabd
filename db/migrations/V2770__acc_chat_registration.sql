SET lock_timeout = '5s';
-- ACC-043: a patient can register from the first WhatsApp message. registration_source says where the record began (existing rows are
-- 'desk'); the desk captures the minimum and asks the patient to finish in chat (chat_completion_requested_at), the chat PATCH stamps
-- profile_completed_at. email and preferred_language are the other fields the chat can fill. Constant defaults: no table rewrite.
ALTER TABLE patients ADD COLUMN registration_source text NOT NULL DEFAULT 'desk';
ALTER TABLE patients ADD COLUMN profile_completed_at timestamptz;
ALTER TABLE patients ADD COLUMN chat_completion_requested_at timestamptz;
ALTER TABLE patients ADD COLUMN email text;
ALTER TABLE patients ADD COLUMN preferred_language text;
ALTER TABLE patients ADD CONSTRAINT patients_registration_source_chk CHECK (registration_source IN ('desk','whatsapp','web','app')) NOT VALID;
ALTER TABLE patients VALIDATE CONSTRAINT patients_registration_source_chk;
