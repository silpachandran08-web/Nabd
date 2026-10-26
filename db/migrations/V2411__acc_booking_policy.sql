SET lock_timeout = '5s';
-- ACC-008: the clinic's booking and queue rules as versioned documents (schema booking-policy.v1, validated in code).
-- One published row, at most one draft; a publish turns the draft into the next version and supersedes the old one.
CREATE TABLE booking_policy_versions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  version      int  NOT NULL CHECK (version > 0),
  status       text NOT NULL CHECK (status IN ('draft','published','superseded')),
  config       jsonb NOT NULL,
  change_note  text CHECK (change_note IS NULL OR length(change_note) <= 200),
  created_by   uuid,
  published_by uuid,
  published_at timestamptz,
  updated_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, version)
);
CREATE UNIQUE INDEX uq_booking_policy_published ON booking_policy_versions (tenant_id) WHERE status = 'published';
CREATE UNIQUE INDEX uq_booking_policy_draft ON booking_policy_versions (tenant_id) WHERE status = 'draft';
ALTER TABLE booking_policy_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_policy_versions
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
SELECT enforce_tenant_rls('booking_policy_versions');

-- existing bookings keep the version they were made under; null = made before any policy was published
ALTER TABLE appointments ADD COLUMN policy_version int;
