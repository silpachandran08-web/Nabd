-- COM-008: resumable per-tenant backfill from Core. One row per (tenant, source table): the keyset position and the
-- counters that the checksum report compares with the source. Re-running a finished marker is a no-op.
CREATE TABLE backfill_markers (
  tenant_id    uuid NOT NULL,
  source_table text NOT NULL CHECK (source_table IN ('whatsapp_templates','whatsapp_messages','whatsapp_inbound_events')),
  last_key     text,                         -- last copied primary key (uuid or bigint as text)
  rows_copied  bigint NOT NULL DEFAULT 0,
  status       text NOT NULL DEFAULT 'running' CHECK (status IN ('running','done','mismatch')),
  source_rows  bigint,
  source_hist  jsonb,                        -- status histogram of the source
  target_hist  jsonb,
  started_at   timestamptz NOT NULL DEFAULT now(),
  finished_at  timestamptz,
  PRIMARY KEY (tenant_id, source_table)
);
ALTER TABLE backfill_markers ENABLE ROW LEVEL SECURITY;
ALTER TABLE backfill_markers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON backfill_markers
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
