SET lock_timeout = '5s';
-- ACC-041: the patient's own confirmation from the reminder (single owner of this column), and the desk exception a
-- "Call me" request creates when a change falls inside the cancellation window.
ALTER TABLE appointments ADD COLUMN patient_confirmed_at timestamptz;

CREATE TABLE appointment_desk_exceptions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  appointment_id uuid NOT NULL REFERENCES appointments(id),
  kind           text NOT NULL CHECK (kind IN ('call_me')),
  status         text NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, appointment_id, kind)
);
ALTER TABLE appointment_desk_exceptions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON appointment_desk_exceptions
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
SELECT enforce_tenant_rls('appointment_desk_exceptions');
