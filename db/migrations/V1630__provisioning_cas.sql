-- PLT-058: provisioning steps run exactly once. unique (job_id, step_name) and (job_id, step_order) already exist (V8).
-- effect_committed_at is the step's idempotency key: written in the same transaction as the step's effect, so a retry
-- after a crash between "effect committed" and "step marked done" sees the effect and only marks the step done.
ALTER TABLE master.provisioning_job_steps ADD COLUMN IF NOT EXISTS effect_committed_at timestamptz;
-- The reaper's scan: only running steps, oldest first.
CREATE INDEX IF NOT EXISTS idx_provisioning_job_steps_running
  ON master.provisioning_job_steps (started_at) WHERE status = 'running';
