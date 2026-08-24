-- NB-259: automatic rollback on provisioning failure. Rolling back create_tenant must never delete
-- an owner/brand that already existed before this job touched them (a job can attach a new clinic
-- to an existing owner) — these two flags are how the rollback knows what it's actually allowed to
-- remove versus what it only borrowed.
ALTER TABLE master.provisioning_jobs ADD COLUMN owner_newly_created boolean NOT NULL DEFAULT false;
ALTER TABLE master.provisioning_jobs ADD COLUMN brand_newly_created boolean NOT NULL DEFAULT false;
