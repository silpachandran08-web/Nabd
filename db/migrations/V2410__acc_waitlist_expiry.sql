SET lock_timeout = '5s';
-- ACC-026: the expiry job scans offers by their deadline.
-- migration-lint: allow MIG-001 small partial index on a table that only holds a handful of live offers per tenant
CREATE INDEX IF NOT EXISTS idx_waitlist_offers_expiring ON waitlist_entries (offer_expires_at) WHERE status = 'offered';
