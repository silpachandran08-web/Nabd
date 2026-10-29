SET lock_timeout = '5s';
-- PLT-030: a clean file attached to a visit (queue entry) as outside report, photo or X-ray.
CREATE TABLE visit_attachments (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  visit_id    uuid NOT NULL REFERENCES queue_entries(id),
  patient_id  uuid NOT NULL REFERENCES patients(id),
  file_id     uuid NOT NULL REFERENCES file_objects(id),
  kind        text NOT NULL CHECK (kind IN ('outside_report', 'photo', 'xray')),
  note        text CHECK (note IS NULL OR length(note) <= 500),
  attached_by uuid REFERENCES staff(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (visit_id, file_id)
);
CREATE INDEX idx_visit_attachments_visit ON visit_attachments (tenant_id, visit_id);
SELECT enforce_tenant_rls('visit_attachments');
