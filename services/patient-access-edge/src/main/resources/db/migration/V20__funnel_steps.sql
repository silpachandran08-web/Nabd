-- ACC-045 (COM-042): booking funnel from conversation states. One row per state change: ids, state names and a time only
-- (no contact hash, no text, no context), so per-clinic drop-off and median booking time need no PHI. Kept 90 days.
CREATE TABLE funnel_steps (
    tenant_id       uuid        NOT NULL,
    conversation_id uuid        NOT NULL,
    from_state      text        NOT NULL,
    to_state        text        NOT NULL,
    at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX funnel_steps_tenant_at_idx ON funnel_steps (tenant_id, at);
ALTER TABLE funnel_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE funnel_steps FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON funnel_steps
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT ON funnel_steps TO edge_app;
    END IF;
END $$;

-- The sweep also drops funnel rows past 90 days. Same function as V7, one more statement.
CREATE OR REPLACE FUNCTION edge_purge_expired() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE n integer := 0; c integer;
BEGIN
    DELETE FROM booking_holds WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM conversations WHERE expires_at <= now();                           GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM processed_messages WHERE processed_at < now() - interval '7 days'; GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM contact_languages WHERE expires_at <= now();                       GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM funnel_steps WHERE at < now() - interval '90 days';                GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    RETURN n;
END $$;
