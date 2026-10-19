-- OPS-004 (DP-13): playbook rule "backfills suppress update triggers", made testable. A backfill or repair job runs
-- SET LOCAL nabd.skip_updated_at = 'on' in its own transaction and set_updated_at() then leaves updated_at alone.
-- The flag is transaction-local, so normal API traffic is unaffected. No schema change, no data change.
SET lock_timeout = '5s';
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  IF coalesce(current_setting('nabd.skip_updated_at', true), '') = 'on' THEN
    RETURN NEW;
  END IF;
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
