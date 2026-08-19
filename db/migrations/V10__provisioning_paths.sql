-- NB-260: self-serve and enterprise provisioning run through the identical job engine (NB-258) —
-- the only difference is a gate. Self-serve jobs have no gate at all; enterprise jobs sit ungated
-- (advance() no-ops) until an operator approves them. DEFAULT 'self_serve' backfills every job
-- created before this migration to the behavior they already had: run immediately, no approval step.
ALTER TABLE master.provisioning_jobs ADD COLUMN path text NOT NULL DEFAULT 'self_serve'
  CHECK (path IN ('self_serve', 'enterprise'));
ALTER TABLE master.provisioning_jobs ADD COLUMN approved_at timestamptz;
ALTER TABLE master.provisioning_jobs ADD COLUMN approved_by uuid REFERENCES master.operators(id);
