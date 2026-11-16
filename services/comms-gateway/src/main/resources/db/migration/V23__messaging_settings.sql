SET lock_timeout = '5s';
-- COM-024: the gateway's copy of Core's messaging settings (owned by Core organization, V3220), written only by
-- organization.messaging_settings.changed.v1 (version-gated) and read by the send scheduler under the row's tenant.
-- No row = no hold (the engine ships dark until the clinic saves its settings). Prayer adhan times are computed in code
-- per evaluation (cheap and deterministic), so there is no prayer_windows_daily cache.
CREATE TABLE tenant_messaging_settings (
  tenant_id         uuid PRIMARY KEY,
  time_zone         text NOT NULL,
  quiet_enabled     boolean NOT NULL,
  quiet_start       time NOT NULL,
  quiet_end         time NOT NULL,
  prayer_enabled    boolean NOT NULL,
  prayer_method     text NOT NULL CHECK (prayer_method IN ('umm_al_qura','qatar','dubai','mwl')),
  prayer_buffer_min int NOT NULL CHECK (prayer_buffer_min BETWEEN 0 AND 60),
  friday_start      time,
  friday_end        time,
  latitude          numeric(8,5),
  longitude         numeric(8,5),
  version           bigint NOT NULL,
  updated_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tenant_messaging_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_messaging_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_messaging_settings
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
