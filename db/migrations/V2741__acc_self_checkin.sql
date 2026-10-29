SET lock_timeout = '5s';
-- ACC-033: self check-in. Facility settings live in their own table (the tenants table has other owners); a clinic without a row has
-- no QR code and no geofence. The patient's location is never stored: checkin_attempts keeps only the distance in metres.
CREATE TABLE facility_checkin_settings (
  tenant_id          uuid PRIMARY KEY,
  checkin_code       text NOT NULL UNIQUE CHECK (checkin_code ~ '^[A-Z0-9]{6,12}$'),
  geo_lat            numeric(9,6) CHECK (geo_lat BETWEEN -90 AND 90),
  geo_lng            numeric(9,6) CHECK (geo_lng BETWEEN -180 AND 180),
  geofence_required  boolean NOT NULL DEFAULT false,
  geofence_radius_m  int NOT NULL DEFAULT 200 CHECK (geofence_radius_m BETWEEN 20 AND 5000),
  walkin_allowed     boolean NOT NULL DEFAULT false,
  wa_number          text CHECK (wa_number ~ '^[0-9]{8,15}$'), -- the clinic's WhatsApp number, digits only, for the door QR deep link
  updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE checkin_attempts (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  appointment_id uuid,
  channel        text NOT NULL CHECK (channel IN ('qr','whatsapp','geofence')),
  distance_m     int,
  outcome        text NOT NULL CHECK (outcome IN ('checked_in','outside_geofence','no_appointment','duplicate')),
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_checkin_attempts_recent ON checkin_attempts (tenant_id, created_at DESC);

-- ACC-032: set when the patient confirms (reminder reply, Edge); an unconfirmed booking close to its start is an exception.
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS confirmed_at timestamptz;

SELECT enforce_tenant_rls('facility_checkin_settings');
SELECT enforce_tenant_rls('checkin_attempts');
