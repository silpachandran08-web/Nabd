SET lock_timeout = '5s';
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_q ON queue_entries(status);
