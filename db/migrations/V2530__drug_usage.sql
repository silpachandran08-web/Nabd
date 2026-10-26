SET lock_timeout = '5s';
-- CLN-020: per-clinic and per-doctor drug usage (drug ids and counts only, no PHI) feeds the typeahead ranking and the
-- client index. doctor_id NULL = the whole clinic. Maintained from the signed-prescription event; drug_usage_applied
-- makes that idempotent per prescription.
CREATE TABLE drug_usage (
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  doctor_id    uuid,
  drug_id      text NOT NULL,
  uses         int NOT NULL DEFAULT 0 CHECK (uses >= 0),
  last_used_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX drug_usage_pk ON drug_usage (tenant_id, COALESCE(doctor_id, '00000000-0000-0000-0000-000000000000'::uuid), drug_id);
CREATE INDEX drug_usage_top ON drug_usage (tenant_id, doctor_id, uses DESC);
SELECT enforce_tenant_rls('drug_usage');

CREATE TABLE drug_usage_applied (
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  prescription_id uuid NOT NULL,
  applied_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, prescription_id)
);
SELECT enforce_tenant_rls('drug_usage_applied');

-- Server typeahead: prefix and substring on the brand (trigram) next to the existing GIN on search_text (V2180).
-- migration-lint: allow MIG-001 drug_master is regional reference data loaded by the importer; CONCURRENTLY hangs Flyway
CREATE INDEX IF NOT EXISTS drug_master_brand_trgm ON drug_master USING gin (lower(brand) gin_trgm_ops);
