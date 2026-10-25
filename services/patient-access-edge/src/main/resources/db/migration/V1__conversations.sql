-- ACC-039: Edge DB (not Core). No clinical data: context holds only ids, first names and ages relayed from Core.
CREATE TABLE conversations (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    channel         text        NOT NULL,
    contact_hash    text        NOT NULL,
    state           text        NOT NULL DEFAULT 'START',
    context         jsonb       NOT NULL DEFAULT '{}',
    last_message_id text,
    version         integer     NOT NULL DEFAULT 0,
    expires_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel, contact_hash)
);
CREATE INDEX conversations_expires_idx ON conversations (expires_at);

CREATE TABLE processed_messages (
    message_id   text        NOT NULL,
    tenant_id    uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, message_id)
);
CREATE INDEX processed_messages_at_idx ON processed_messages (processed_at);

ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON conversations
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE processed_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON processed_messages
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
