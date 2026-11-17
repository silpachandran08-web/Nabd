SET lock_timeout = '5s';
-- COM-044: package card, next-session nudge and expiry warnings are real messages (COM-004 notification contract).
-- package_instance_events keeps the notification request id and its delivery status (synced from comms.message.status_changed.v1),
-- so the ledger and the desk show 'delivered' / 'read' / 'blocked' instead of a bare 'sent'.
ALTER TABLE package_instance_events
  ADD COLUMN notification_request_id uuid,
  ADD COLUMN delivery_status text CHECK (delivery_status IN ('requested', 'blocked', 'delivered', 'read', 'failed', 'cancelled', 'expired'));
ALTER TABLE package_instance_events DROP CONSTRAINT package_instance_events_event_type_check;
ALTER TABLE package_instance_events ADD CONSTRAINT package_instance_events_event_type_check CHECK (event_type IN (
  'sold', 'payment_received', 'invoice_issued', 'session_booked', 'session_redeemed',
  'expiry_warning_sent', 'extended', 'refunded', 'cancelled',
  'card_requested', 'session_nudge', 'grace_notice')) NOT VALID;
ALTER TABLE package_instance_events VALIDATE CONSTRAINT package_instance_events_event_type_check;
-- migration-lint: allow MIG-001 the column is new (all NULL), the partial index is empty at creation; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX idx_package_instance_events_request ON package_instance_events (tenant_id, notification_request_id)
  WHERE notification_request_id IS NOT NULL;

-- The doctor-set interval between sessions; the job nudges on next_session_nudge_at (set at sale and after each redemption).
ALTER TABLE package_instances
  ADD COLUMN nudge_interval_days int CHECK (nudge_interval_days BETWEEN 1 AND 365),
  ADD COLUMN next_session_nudge_at date;

-- 'Book session N of M' from WhatsApp: the booked session is linked to its appointment; a patient-channel booking has no staff booker.
ALTER TABLE package_redemptions
  ADD COLUMN appointment_id uuid,
  ALTER COLUMN booked_by DROP NOT NULL;
-- migration-lint: allow MIG-001 the column is new (all NULL), the partial index is empty at creation; CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX uq_package_redemptions_appointment ON package_redemptions (tenant_id, appointment_id) WHERE appointment_id IS NOT NULL;

-- In-process sends (no Comms Gateway yet) report status by WhatsApp message id: keep the message a request turned into.
ALTER TABLE notification_requests ADD COLUMN message_id uuid;
-- migration-lint: allow MIG-001 the column is new (all NULL), the partial index is empty at creation; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX idx_notification_requests_message ON notification_requests (tenant_id, message_id) WHERE message_id IS NOT NULL;

-- The package card is an IDC-015 secure link whose content is the card itself (no stored file).
ALTER TABLE secure_links DROP CONSTRAINT secure_links_document_type_check;
ALTER TABLE secure_links ADD CONSTRAINT secure_links_document_type_check
  CHECK (document_type IN ('rx', 'invoice', 'report', 'document', 'package_card')) NOT VALID;
ALTER TABLE secure_links VALIDATE CONSTRAINT secure_links_document_type_check;

-- A nudge blocked for lack of messaging consent lands on the desk's exceptions list (one open item per package).
ALTER TABLE desk_exceptions DROP CONSTRAINT desk_exceptions_kind_check;
ALTER TABLE desk_exceptions ADD CONSTRAINT desk_exceptions_kind_check
  CHECK (kind IN ('checkin_failed', 'unconfirmed_booking', 'request_sla', 'checkout_unpaid', 'handoff', 'package_nudge_blocked')) NOT VALID;
ALTER TABLE desk_exceptions VALIDATE CONSTRAINT desk_exceptions_kind_check;
ALTER TABLE desk_exceptions DROP CONSTRAINT desk_exceptions_ref_type_check;
ALTER TABLE desk_exceptions ADD CONSTRAINT desk_exceptions_ref_type_check
  CHECK (ref_type IN ('appointment', 'checkin_attempt', 'queue_entry', 'conversation', 'package_instance')) NOT VALID;
ALTER TABLE desk_exceptions VALIDATE CONSTRAINT desk_exceptions_ref_type_check;

-- Starter templates (COM-012 catalogue): package_card and package_expiry are already in V1881; the nudge and the grace notice are new. Utility wording, no package name (it can reveal the treatment), no amounts.
-- No body starts or ends with a variable (Meta rule). Hindi wording and Meta approval are Needs external action, like the rest of the catalogue.
INSERT INTO template_catalogue (key, language, category, body, example_values, slot_types, purpose) VALUES
('package_session_nudge','en','UTILITY','Hello {{1}}, it is time for your next package session. Reply to this message to book a time.',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Reminds the patient to book the next package session'),
('package_session_nudge','hi','UTILITY','नमस्ते {{1}}, आपके पैकेज के अगले सेशन का समय हो गया है। समय बुक करने के लिए इस संदेश का जवाब दें।',ARRAY['Asha']::text[],ARRAY['patient_first_name']::text[],'Reminds the patient to book the next package session'),
('package_grace','en','UTILITY','Hello {{1}}, your package validity has ended. Until {{2}}, you can still use your remaining sessions.',ARRAY['Asha','19 March']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient the package is in its grace period'),
('package_grace','hi','UTILITY','नमस्ते {{1}}, आपके पैकेज की वैधता समाप्त हो गई है। आप {{2}} तक अपने बचे हुए सेशन अभी भी उपयोग कर सकते हैं।',ARRAY['Asha','19 March']::text[],ARRAY['patient_first_name','date']::text[],'Tells the patient the package is in its grace period');
