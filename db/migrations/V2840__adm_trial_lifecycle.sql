SET lock_timeout = '5s';
-- ADM-004: trial clock on the subscription, per-plan trial length, the owner's "Start for real" job, and a lifecycle event that
-- a staff member or the system (not only a platform operator) can cause. Expand-only.

ALTER TABLE master.plans ADD COLUMN trial_days int NOT NULL DEFAULT 14 CHECK (trial_days BETWEEN 1 AND 90);

-- status default 'active': every existing subscription is a paid/real one. trial_reminded_days = the smallest reminder
-- threshold (7, 3, 1) already sent, so a re-run of the daily job never repeats a reminder.
ALTER TABLE master.subscriptions
  ADD COLUMN trial_ends_at timestamptz,
  ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('trialing','active','past_due','cancelled','free')),
  ADD COLUMN trial_reminded_days int;
-- migration-lint: allow MIG-001 CONCURRENTLY hangs inside the Flyway transaction; partial index over rows that are 'active' today (no trialing row exists yet)
CREATE INDEX idx_subscriptions_trial_ends ON master.subscriptions (trial_ends_at) WHERE status = 'trialing';

-- A lifecycle change caused by the clinic owner (go live) or by the trial job has no master.operators row.
ALTER TABLE tenant_lifecycle_events ALTER COLUMN changed_by DROP NOT NULL;
ALTER TABLE tenant_lifecycle_events ADD COLUMN changed_by_staff uuid;

CREATE TABLE trial_conversion_jobs (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id             uuid NOT NULL REFERENCES tenants(id),
  requested_by          uuid NOT NULL REFERENCES staff(id),
  status                text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed')),
  synthetic_rows_deleted bigint NOT NULL DEFAULT 0,
  error_code            text,
  created_at            timestamptz NOT NULL DEFAULT now(),
  completed_at          timestamptz
);
-- one open job per clinic, and a clinic converts once
CREATE UNIQUE INDEX uq_trial_conversion_open ON trial_conversion_jobs (tenant_id) WHERE status IN ('queued','running');
CREATE UNIQUE INDEX uq_trial_conversion_done ON trial_conversion_jobs (tenant_id) WHERE status = 'done';
SELECT enforce_tenant_rls('trial_conversion_jobs');
