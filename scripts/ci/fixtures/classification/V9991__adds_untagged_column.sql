-- PLT-052 failing sample: two columns nobody tagged in db/classification.yaml.
SET lock_timeout = '5s';
ALTER TABLE patients ADD COLUMN IF NOT EXISTS blood_group text;
CREATE TABLE identity.person_links (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  CONSTRAINT person_links_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
