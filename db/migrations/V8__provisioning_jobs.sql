-- NB-258: tenant provisioning as a resumable job, not a wizard. The six steps
-- (create_tenant, migrate_schema, seed_masters, provision_whatsapp,
-- verify_invite_owner, go_live) are rows, not code branches, so a job is
-- inspectable and resumable at every step (SSA-01, SYS-21).
--
-- Lives in master, not public: a provisioning job is platform-operator
-- infrastructure, not tenant data, same rationale as V7's operators/sessions.
-- It still references public.tenants/owners/brands once those rows exist —
-- cross-schema FKs are fine in Postgres, just qualified explicitly here since
-- master is not on every connection's default search_path.

CREATE TABLE master.provisioning_jobs (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requested_by      uuid NOT NULL REFERENCES master.operators(id),
  tenant_slug       citext NOT NULL,
  tenant_name       text NOT NULL,
  region            text NOT NULL CHECK (region IN ('IN', 'KSA')),
  owner_email       citext NOT NULL,
  owner_name        text NOT NULL,
  brand_name        text NOT NULL,
  -- 'rolled_back' has no writer yet — NB-259 (automatic rollback on failure)
  -- is the consumer, same "schema ships ready, code catches up" precedent as
  -- V6's app.accessible_tenant_ids RLS clause.
  status            text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed', 'rolled_back')),
  created_tenant_id uuid REFERENCES public.tenants(id),
  created_owner_id  uuid REFERENCES public.owners(id),
  created_brand_id  uuid REFERENCES public.brands(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_provisioning_jobs_updated_at BEFORE UPDATE ON master.provisioning_jobs
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE INDEX idx_provisioning_jobs_requested_by ON master.provisioning_jobs (requested_by);

CREATE TABLE master.provisioning_job_steps (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id        uuid NOT NULL REFERENCES master.provisioning_jobs(id),
  step_name     text NOT NULL CHECK (step_name IN (
                  'create_tenant', 'migrate_schema', 'seed_masters',
                  'provision_whatsapp', 'verify_invite_owner', 'go_live'
                )),
  step_order    smallint NOT NULL,
  status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed', 'rolled_back')),
  started_at    timestamptz,
  completed_at  timestamptz,
  error_detail  text,
  UNIQUE (job_id, step_name)
);
CREATE INDEX idx_provisioning_job_steps_job ON master.provisioning_job_steps (job_id, step_order);
