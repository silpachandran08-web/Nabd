-- ACC-040: the language a contact chose once. The key is the hashed number (as in conversations); no name, no health data.
-- Kept 180 days from the last use, then swept (V7).
CREATE TABLE contact_languages (
    tenant_id    uuid        NOT NULL,
    channel      text        NOT NULL,
    contact_hash text        NOT NULL,
    lang         text        NOT NULL CHECK (lang IN ('en', 'hi', 'kn', 'te', 'ar')),
    expires_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, channel, contact_hash)
);
CREATE INDEX contact_languages_expires_idx ON contact_languages (expires_at);

ALTER TABLE contact_languages ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_languages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contact_languages
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'edge_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON contact_languages TO edge_app;
    END IF;
END $$;
