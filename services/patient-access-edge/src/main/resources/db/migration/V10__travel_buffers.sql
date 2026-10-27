-- ACC-041: how long the patient takes to reach the clinic; asked once, reused for the leave-home alerts (ACC-044).
-- patient_ref is Core's opaque reference (ACC-038), never a name or phone.
CREATE TABLE travel_buffers (
    tenant_id   uuid        NOT NULL,
    patient_ref text        NOT NULL,
    minutes     int         NOT NULL CHECK (minutes IN (10, 20, 30, 45, 60)),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, patient_ref)
);
ALTER TABLE travel_buffers ENABLE ROW LEVEL SECURITY;
ALTER TABLE travel_buffers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON travel_buffers
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON travel_buffers TO edge_app;
    END IF;
END $$;
