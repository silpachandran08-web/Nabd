SET lock_timeout = '5s';
-- COM-032: canned replies, call-outcome notes, staff free-text sessions replies, clinical-tag suggestion and an audit row per staff action.
-- Comms has its own database, so the audit rows live here (text is never stored in them, only its SHA-256).
CREATE TABLE canned_replies (
  id         uuid PRIMARY KEY,
  tenant_id  uuid NOT NULL,
  title      text NOT NULL,
  body       text NOT NULL,
  language   text NOT NULL,
  tags       text[] NOT NULL DEFAULT '{}',
  created_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_canned_replies_tenant_lang ON canned_replies (tenant_id, language);
ALTER TABLE canned_replies ENABLE ROW LEVEL SECURITY;
ALTER TABLE canned_replies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON canned_replies
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE thread_notes (
  id        uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  thread_id uuid NOT NULL REFERENCES inbox_threads (id),
  staff_ref uuid NOT NULL,
  outcome   text NOT NULL CHECK (outcome IN ('reached','no_answer','call_back','booked')),
  note_enc  bytea,
  at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_thread_notes_thread ON thread_notes (tenant_id, thread_id, at);
ALTER TABLE thread_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE thread_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON thread_notes
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE inbox_audit (
  id        uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  thread_id uuid NOT NULL,
  staff_ref uuid NOT NULL,
  action    text NOT NULL CHECK (action IN ('reply','template_send','assign','mode','note','tag_clinical')),
  text_hash bytea,
  detail    text,
  at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_inbox_audit_thread ON inbox_audit (tenant_id, thread_id, at);
ALTER TABLE inbox_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbox_audit
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE outbound_messages ADD COLUMN free_text_enc bytea;
ALTER TABLE outbound_messages ADD COLUMN author_staff_ref uuid;
ALTER TABLE outbound_messages ADD COLUMN idempotency_key text;
-- migration-lint: allow MIG-001 comms database, partial unique index on a column added in this file (no rows have it)
CREATE UNIQUE INDEX idx_outbound_messages_idem ON outbound_messages (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
ALTER TABLE inbox_threads ADD COLUMN clinical_suggested boolean NOT NULL DEFAULT false;
