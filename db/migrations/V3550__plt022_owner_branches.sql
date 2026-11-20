-- PLT-022: an owner adds a branch (a new clinic under the same brand and region) from their own console.
-- 1. tenants: branch code (unique per brand), label and address. 2. config_bundle_imports: what was copied into a new
-- branch from which source clinic. 3. master.provisioning_jobs: an owner-started job has no operator, names its source
-- clinic and the sections to copy, and gets the extra step copy_config. Additive only.
SET lock_timeout = '5s';

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS branch_code text;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS branch_label text;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS branch_address text;
-- migration-lint: allow MIG-001 tenants holds one row per clinic; CONCURRENTLY cannot run in the Flyway transaction
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_brand_branch_code ON tenants (brand_id, lower(branch_code)) WHERE branch_code IS NOT NULL;

CREATE TABLE config_bundle_imports (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL REFERENCES tenants(id),
  source_tenant_id uuid NOT NULL REFERENCES tenants(id),
  sections         text[] NOT NULL,
  status           text NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
  started_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  error            text
);
CREATE INDEX idx_config_bundle_imports_tenant ON config_bundle_imports (tenant_id, started_at);
SELECT enforce_tenant_rls('config_bundle_imports');

ALTER TABLE master.provisioning_jobs ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS source_tenant_id uuid REFERENCES public.tenants(id);
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS requested_by_staff uuid;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS planned_tenant_id uuid;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS branch_code text;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS branch_address text;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS timezone text;
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS copy_sections text[];
-- the step-up confirmation that authorised the job: unique, so one confirmation starts one branch
ALTER TABLE master.provisioning_jobs ADD COLUMN IF NOT EXISTS step_up_id uuid UNIQUE;
ALTER TABLE master.provisioning_jobs ADD CONSTRAINT provisioning_jobs_requester_check
  CHECK (requested_by IS NOT NULL OR (requested_by_staff IS NOT NULL AND source_tenant_id IS NOT NULL)) NOT VALID;
ALTER TABLE master.provisioning_jobs VALIDATE CONSTRAINT provisioning_jobs_requester_check;

ALTER TABLE master.provisioning_job_steps DROP CONSTRAINT IF EXISTS provisioning_job_steps_step_name_check;
ALTER TABLE master.provisioning_job_steps ADD CONSTRAINT provisioning_job_steps_step_name_check CHECK (step_name IN (
  'create_tenant', 'migrate_schema', 'seed_masters', 'provision_whatsapp', 'verify_invite_owner', 'go_live', 'copy_config'
)) NOT VALID;
ALTER TABLE master.provisioning_job_steps VALIDATE CONSTRAINT provisioning_job_steps_step_name_check;
