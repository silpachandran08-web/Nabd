-- OPS-015: wake the relay (OPS-016, LISTEN outbox) when events commit. FOR EACH STATEMENT, and Postgres folds
-- identical NOTIFYs (same channel, same payload) within one transaction, so a business transaction that publishes
-- N events delivers exactly one notification, at commit; a rolled-back transaction delivers none.
SET lock_timeout = '5s';

CREATE FUNCTION outbox_notify() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('outbox', '');
  RETURN NULL;
END $$;
CREATE TRIGGER trg_outbox_notify AFTER INSERT ON outbox_events
  FOR EACH STATEMENT EXECUTE FUNCTION outbox_notify();
