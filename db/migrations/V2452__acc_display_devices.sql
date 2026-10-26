SET lock_timeout = '5s';
-- ACC-023: lobby TV pairing. Codes and device tokens are stored as SHA-256 hashes only. Scope = {doctorIds?, departmentId?}.
-- display_pairing_lockout counts wrong codes per tenant (5 -> locked 15 minutes). No names, no phone numbers anywhere.
CREATE TABLE display_devices (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  label        text NOT NULL,
  scope        jsonb NOT NULL DEFAULT '{}',
  token_hash   text NOT NULL,
  paired_by    uuid,
  paired_at    timestamptz NOT NULL,
  last_seen_at timestamptz,
  revoked_at   timestamptz
);
CREATE INDEX idx_display_devices_tenant ON display_devices (tenant_id, paired_at DESC);
CREATE TABLE display_pairing_codes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  uuid NOT NULL REFERENCES tenants(id),
  code_hash  text NOT NULL,
  scope      jsonb NOT NULL DEFAULT '{}',
  label      text NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  created_by uuid
);
CREATE INDEX idx_display_pairing_codes_tenant ON display_pairing_codes (tenant_id, expires_at DESC);
CREATE TABLE display_pairing_lockout (
  tenant_id    uuid PRIMARY KEY REFERENCES tenants(id),
  failures     int NOT NULL DEFAULT 0,
  locked_until timestamptz
);
SELECT enforce_tenant_rls('display_devices');
SELECT enforce_tenant_rls('display_pairing_codes');
SELECT enforce_tenant_rls('display_pairing_lockout');

-- the two languages a lobby display shows come from data, not from code: English plus the region's local language
ALTER TABLE regions ADD COLUMN display_languages text[] NOT NULL DEFAULT '{en}';
UPDATE regions SET display_languages = '{en,hi}' WHERE code = 'IN';
UPDATE regions SET display_languages = '{en,ar}' WHERE code IN ('AE','SA','QA');
