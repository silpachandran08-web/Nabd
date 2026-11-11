SET lock_timeout = '5s';
-- COM-043: reminder cascade state of an appointment (ACC-041 owns patient_confirmed_at, ACC-007 cancel_reason_code).
-- none -> (T-24 h reminder, no answer by the clinic cutoff) nudged -> (still none at T-2 h) unconfirmed_flagged; confirmed
-- whenever the patient taps Confirm.
ALTER TABLE appointments ADD COLUMN confirmation_state text NOT NULL DEFAULT 'none'
  CHECK (confirmation_state IN ('none', 'nudged', 'confirmed', 'unconfirmed_flagged'));
UPDATE appointments SET confirmation_state = 'confirmed' WHERE patient_confirmed_at IS NOT NULL;

-- One row per appointment and reminder kind (t24h, t2h, nudge) once its notification was requested: the minute sweep never asks
-- twice, and a rescheduled booking (a new appointment row) gets its own reminders. Ids only.
CREATE TABLE appointment_reminders (
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  appointment_id uuid NOT NULL REFERENCES appointments(id),
  kind           text NOT NULL CHECK (kind ~ '^(t[0-9]{1,3}h|nudge)$'),
  request_id     uuid NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, appointment_id, kind)
);
SELECT enforce_tenant_rls('appointment_reminders');

-- The unconfirmed nudge (COM-043 cascade). Generic utility wording, no clinical content. NEEDS EXTERNAL ACTION: native-speaker
-- review of the Hindi text and Meta approval of both; KN/TE are not written yet (clinics in those states get no nudge until added).
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('appt_nudge','en','UTILITY','Hello {{1}}, we have not heard from you about your appointment on {{2}} at {{3}}. Please reply to confirm, or contact us to change it.',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Asks for a confirmation the patient has not given yet'),
('appt_nudge','hi','UTILITY','नमस्ते {{1}}, {{2}} को {{3}} बजे की आपकी अपॉइंटमेंट के बारे में हमें आपका जवाब नहीं मिला। कृपया पुष्टि के लिए जवाब दें, या बदलने के लिए हमसे संपर्क करें।',ARRAY['Asha','12 March','10:30']::text[],ARRAY['patient_first_name','date','time']::text[],'Asks for a confirmation the patient has not given yet');
