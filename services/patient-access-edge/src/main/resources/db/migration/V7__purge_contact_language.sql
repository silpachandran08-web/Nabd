-- ACC-040: the V3 sweep also removes language choices past their 180 days. Same function, one more statement.
CREATE OR REPLACE FUNCTION edge_purge_expired() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE n integer := 0; c integer;
BEGIN
    DELETE FROM booking_holds WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM conversations WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM processed_messages WHERE processed_at < now() - interval '7 days'; GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM contact_languages WHERE expires_at <= now();                       GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    RETURN n;
END $$;
