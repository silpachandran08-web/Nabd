SET lock_timeout = '5s';
-- COM-023: scheduled sends live in outbound_messages (status 'scheduled'). The scheduler reads them across tenants (it
-- must know whose row is due) through one SELECT-only policy switched on per transaction, like phone_routes (V5);
-- every write still runs under the row's own tenant.
ALTER TABLE outbound_messages ADD COLUMN scheduled_at timestamptz;
-- migration-lint: allow MIG-001 comms database, partial index on a table that holds no production rows before this story
CREATE INDEX idx_outbound_messages_scheduled ON outbound_messages (status, not_before) WHERE status = 'scheduled';
CREATE POLICY scheduler_scan ON outbound_messages FOR SELECT
  USING (status = 'scheduled' AND current_setting('app.scheduler', true) = 'on');
