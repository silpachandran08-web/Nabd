SET lock_timeout = '5s';
-- IDC-016 (DEC-33): ABDM is opt-in and off by default per clinic. hfr_id is the facility's Health Facility Registry id;
-- hip_id is filled when the HIP link is registered with the gateway (later story). Credentials never live here: they sit in
-- the regional secret store (OPS-058).
CREATE TABLE tenant_integrations_abdm (
  tenant_id    uuid PRIMARY KEY REFERENCES tenants(id),
  enabled      boolean NOT NULL DEFAULT false,
  hfr_id       text CHECK (hfr_id IS NULL OR hfr_id ~ '^[A-Za-z0-9_-]{3,40}$'),
  hip_id       text,
  verified_at  timestamptz,
  updated_by   uuid REFERENCES staff(id),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CHECK (NOT enabled OR (hfr_id IS NOT NULL AND verified_at IS NOT NULL))
);
SELECT enforce_tenant_rls('tenant_integrations_abdm');

-- HPR id of a doctor (verified against the Health Professional Registry); practitioner registration is IDC-037.
ALTER TABLE staff ADD COLUMN hpr_id text, ADD COLUMN hpr_verified_at timestamptz;
ALTER TABLE staff ADD CONSTRAINT staff_hpr_id_check CHECK (hpr_id IS NULL OR hpr_id ~ '^[A-Za-z0-9@._-]{3,60}$') NOT VALID;
ALTER TABLE staff VALIDATE CONSTRAINT staff_hpr_id_check;
