SET lock_timeout = '5s';
-- PLT-045: one data-encryption key (DEK) per tenant, stored only wrapped by the region's KMS key. FieldCipher format v2
-- selects the DEK by kid; crypto-shred sets status 'destroyed' and empties wrapped_dek. kms_key_ref names the region key
-- version that wrapped the DEK (re-wrapped to the newest version by the rotation job, rotated_at records when).
CREATE TABLE tenant_data_keys (
  tenant_id    uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  kid          text        NOT NULL CHECK (kid ~ '^[0-9]{1,3}$'),
  wrapped_dek  bytea       NOT NULL,
  kms_key_ref  text        NOT NULL,
  status       text        NOT NULL CHECK (status IN ('active', 'retired', 'destroyed')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  rotated_at   timestamptz,
  destroyed_at timestamptz,
  PRIMARY KEY (tenant_id, kid)
);
CREATE UNIQUE INDEX uq_tenant_data_keys_active ON tenant_data_keys (tenant_id) WHERE status = 'active';
SELECT enforce_tenant_rls('tenant_data_keys');

-- Region level key metadata for the platform regions screen (key age, last rotation). No tenant data.
CREATE TABLE kms_region_keys (
  region_code     char(2)     PRIMARY KEY REFERENCES regions(code),
  current_key_ref text        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  last_rotated_at timestamptz
);
