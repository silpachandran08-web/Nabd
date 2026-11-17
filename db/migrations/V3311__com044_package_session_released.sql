SET lock_timeout = '5s';
-- COM-044 (S24 T1-BUG-3): a booked session whose appointment is cancelled, declined or marked a no-show is released
-- (package_redemptions.status 'booked' -> 'cancelled', a value V27 already allows); the package ledger says so.
ALTER TABLE package_instance_events DROP CONSTRAINT package_instance_events_event_type_check;
ALTER TABLE package_instance_events ADD CONSTRAINT package_instance_events_event_type_check CHECK (event_type IN (
  'sold', 'payment_received', 'invoice_issued', 'session_booked', 'session_redeemed',
  'expiry_warning_sent', 'extended', 'refunded', 'cancelled',
  'card_requested', 'session_nudge', 'grace_notice', 'session_released')) NOT VALID;
ALTER TABLE package_instance_events VALIDATE CONSTRAINT package_instance_events_event_type_check;
