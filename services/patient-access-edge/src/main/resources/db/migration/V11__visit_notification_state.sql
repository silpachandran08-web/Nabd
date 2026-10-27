-- ACC-044: what the patient was already told about one visit (hysteresis state of the leave-now / ETA-shift rules).
-- appointment_ref is Core's appointment id; contact_hash and patient_ref are the Edge's own opaque keys. No time zone, name or text.
CREATE TABLE visit_notification_state (
    tenant_id           uuid        NOT NULL,
    appointment_ref     text        NOT NULL,
    contact_hash        text        NOT NULL,
    patient_ref         text        NOT NULL,
    current_p50         timestamptz,
    display_mode        text        NOT NULL DEFAULT 'range',
    arrived             boolean     NOT NULL DEFAULT false,
    last_notified_p50   timestamptz,
    messages_sent       int         NOT NULL DEFAULT 0,
    leave_now_sent_at   timestamptz,
    last_shift_at       timestamptz,
    pending_shift_since timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, appointment_ref)
);
ALTER TABLE visit_notification_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit_notification_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit_notification_state
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The timer sweep must see every tenant (same pattern as edge_purge_expired, V3): returns the tracked visits that can still get a message
-- and drops rows untouched for two days.
CREATE FUNCTION edge_due_visits() RETURNS TABLE (tenant_id uuid, appointment_ref text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    DELETE FROM visit_notification_state v WHERE v.updated_at < now() - interval '2 days';
    RETURN QUERY SELECT v.tenant_id, v.appointment_ref FROM visit_notification_state v
        WHERE NOT v.arrived AND v.messages_sent < 3;
END $$;
REVOKE ALL ON FUNCTION edge_due_visits() FROM PUBLIC;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON visit_notification_state TO edge_app;
        GRANT EXECUTE ON FUNCTION edge_due_visits() TO edge_app;
    END IF;
END $$;
