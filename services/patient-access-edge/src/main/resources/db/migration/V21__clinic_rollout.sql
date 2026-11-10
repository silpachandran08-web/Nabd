-- ACC-045: staged rollout to cohort B. A clinic's bot is switched on per clinic, and the database refuses to switch it on
-- before its onboarding checklist is complete (WhatsApp number live, templates approved, QR poster with the click-to-chat
-- link handed over). A clinic with no row follows edge.rollout.default-enabled (true while cohort A has no rows; set it to
-- false once every cohort A clinic has its row, see docs/runbooks/edge-cohort-b.md).
CREATE TABLE clinic_rollout (
    tenant_id          uuid        PRIMARY KEY,
    number_live        boolean     NOT NULL DEFAULT false,
    templates_approved boolean     NOT NULL DEFAULT false,
    qr_poster_issued   boolean     NOT NULL DEFAULT false,
    enabled            boolean     NOT NULL DEFAULT false,
    enabled_by         text,
    enabled_at         timestamptz,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT clinic_rollout_checklist_before_enable
        CHECK (NOT enabled OR (number_live AND templates_approved AND qr_poster_issued AND enabled_by IS NOT NULL AND enabled_at IS NOT NULL))
);
ALTER TABLE clinic_rollout ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinic_rollout FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON clinic_rollout
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT ON clinic_rollout TO edge_app;  -- read only: the checklist and the switch are changed by the migrator role (runbook)
    END IF;
END $$;
