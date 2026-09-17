-- Reception's walk-in registration form (arrivals page) captures a presenting complaint and an
-- expected payment mode at check-in time — neither had anywhere to live. payment_mode reuses
-- invoice_payments' own method vocabulary (cash/card/upi/other) for consistency, even though this
-- is captured earlier and is just what reception expects, not the actual payment record.
ALTER TABLE queue_entries
  ADD COLUMN presenting_complaint text,
  ADD COLUMN payment_mode text
    CONSTRAINT queue_entries_payment_mode_check CHECK (payment_mode = ANY (ARRAY['cash', 'card', 'upi', 'other']));
