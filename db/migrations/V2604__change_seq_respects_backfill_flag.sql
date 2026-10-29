SET lock_timeout = '5s';
-- OPS-006 x OPS-004: a backfill/repair that runs SET LOCAL nabd.skip_updated_at = 'on' must change nothing but its own columns,
-- so on UPDATE the trigger now keeps the old change_seq (and updated_at) instead of taking a new sequence value. INSERTs and
-- normal traffic are unchanged.
CREATE OR REPLACE FUNCTION set_change_seq() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND coalesce(current_setting('nabd.skip_updated_at', true), '') = 'on' THEN
    NEW.change_seq := OLD.change_seq;
    RETURN NEW;
  END IF;
  NEW.change_seq := nextval('change_seq_global');
  IF TG_OP = 'UPDATE' THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END;
$$;
