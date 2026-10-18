CREATE INDEX ix_q ON queue_entries(status);
ALTER TABLE queue_entries ADD CONSTRAINT c CHECK (a>0);
ALTER TABLE queue_entries ALTER COLUMN a SET NOT NULL;
ALTER TABLE queue_entries DROP COLUMN b;
