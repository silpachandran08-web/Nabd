SET lock_timeout = '5s';
-- COM-024: the clinic's prayer-window settings for patient messages (quiet hours stay in clinic_quiet_hours, ADM-028).
-- Owned by organization.setup; every change is mirrored to the Comms Gateway by organization.messaging_settings.changed.v1.
-- No row = the region's defaults (prayer windows on for SA, off elsewhere; no coordinates yet). Non-PHI.
-- NEEDS LOCAL CHECK: the calculation methods and the Friday block default (11:30-13:30) await a GCC reviewer.
CREATE TABLE tenant_messaging_settings (
  tenant_id         uuid PRIMARY KEY REFERENCES tenants (id),
  prayer_enabled    boolean NOT NULL,
  prayer_method     text NOT NULL CHECK (prayer_method IN ('umm_al_qura', 'qatar', 'dubai', 'mwl')),
  prayer_buffer_min int NOT NULL DEFAULT 20 CHECK (prayer_buffer_min BETWEEN 0 AND 60),
  friday_start      time NOT NULL DEFAULT '11:30',
  friday_end        time NOT NULL DEFAULT '13:30',
  latitude          numeric(8,5) CHECK (latitude BETWEEN -66 AND 66),
  longitude         numeric(8,5) CHECK (longitude BETWEEN -180 AND 180),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CHECK (friday_start < friday_end),
  CHECK (NOT prayer_enabled OR (latitude IS NOT NULL AND longitude IS NOT NULL))
);
SELECT enforce_tenant_rls('tenant_messaging_settings');
