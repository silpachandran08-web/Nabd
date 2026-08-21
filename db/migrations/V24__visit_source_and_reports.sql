-- NB-079: exactly one source tag per visit, enforced at write time by being a single column with
-- a CHECK constraint — never a dual axis. Lives on queue_entries (one row per visit).
ALTER TABLE queue_entries ADD COLUMN source text NOT NULL DEFAULT 'walk_in'
  CHECK (source IN ('walk_in', 'referral', 'online', 'social_media', 'returning', 'other'));

-- NB-231/234/235/237 (E20 Owner Insights, scoped to live queries — see ReportsRepository) read
-- straight off invoices/invoice_payments/queue_entries/patients. No new tables needed.
