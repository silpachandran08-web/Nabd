SET lock_timeout = '5s';
-- ACC-032: the front desk's exceptions inbox. One open row per (kind, ref); summary_params holds ids and codes only, never free text.
CREATE TABLE desk_exceptions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  kind           text NOT NULL CHECK (kind IN ('checkin_failed','unconfirmed_booking','request_sla','checkout_unpaid','handoff')),
  severity       text NOT NULL CHECK (severity IN ('info','warn','urgent')),
  ref_type       text NOT NULL CHECK (ref_type IN ('appointment','checkin_attempt','queue_entry','conversation')),
  ref_id         uuid NOT NULL,
  summary_params jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz NOT NULL DEFAULT now(),
  resolved_at    timestamptz,
  resolved_by    uuid,
  resolution     text CHECK (resolution IN ('auto','manual')),
  snoozed_until  timestamptz
);
CREATE UNIQUE INDEX uq_desk_exceptions_open ON desk_exceptions (tenant_id, kind, ref_id) WHERE resolved_at IS NULL;
CREATE INDEX idx_desk_exceptions_open ON desk_exceptions (tenant_id, created_at) WHERE resolved_at IS NULL;
SELECT enforce_tenant_rls('desk_exceptions');
