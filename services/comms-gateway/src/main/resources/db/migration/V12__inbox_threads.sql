SET lock_timeout = '5s';
-- COM-030: one thread per tenant + sender number + patient phone, and the parsed inbound messages (text encrypted).
-- The spec names this V20; the E16 Comms range is V10-V13. window_expires_at is a stored generated column; the
-- AT TIME ZONE round trip keeps the expression immutable (timestamptz + interval is only stable).
CREATE TABLE inbox_threads (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  sender_account_id   uuid NOT NULL,
  phone_hash          bytea NOT NULL,
  phone_enc           bytea NOT NULL,
  patient_ref         uuid,
  family_patient_refs uuid[] NOT NULL DEFAULT '{}',
  last_inbound_at     timestamptz,
  last_outbound_at    timestamptz,
  window_expires_at   timestamptz GENERATED ALWAYS AS (((last_inbound_at AT TIME ZONE 'UTC') + interval '24 hours') AT TIME ZONE 'UTC') STORED,
  unread_count        int NOT NULL DEFAULT 0 CHECK (unread_count >= 0),
  waiting_since       timestamptz,
  mode                text NOT NULL DEFAULT 'human' CHECK (mode IN ('bot','human')),
  assigned_staff_ref  uuid,
  state               text NOT NULL DEFAULT 'open' CHECK (state IN ('open','closed')),
  opted_out           boolean NOT NULL DEFAULT false,
  tags                text[] NOT NULL DEFAULT '{}',
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, sender_account_id, phone_hash)
);
CREATE INDEX idx_inbox_threads_waiting ON inbox_threads (tenant_id, state, waiting_since);
ALTER TABLE inbox_threads ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox_threads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbox_threads
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE inbound_messages (
  id             uuid PRIMARY KEY,
  seq            bigint GENERATED ALWAYS AS IDENTITY, -- the integer inboundId of comms.inbound.received.v1
  tenant_id      uuid NOT NULL,
  thread_id      uuid NOT NULL REFERENCES inbox_threads (id),
  wamid          text NOT NULL UNIQUE,
  kind           text NOT NULL CHECK (kind IN ('text','button','interactive','image','audio','document','location','reaction')),
  text_enc       bytea,
  button_payload text,
  media_ref      uuid,
  context_wamid  text,
  received_at    timestamptz NOT NULL
);
CREATE INDEX idx_inbound_messages_thread ON inbound_messages (tenant_id, thread_id, received_at);
ALTER TABLE inbound_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbound_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbound_messages
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
