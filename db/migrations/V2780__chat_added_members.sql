SET lock_timeout = '5s';
-- IDC-010: a caregiver adds a family member in the WhatsApp chat. chat_added_members records who added whom, the relation,
-- and the proxy state the chat flow ended in; patient_desk_tasks holds work for the desk (confirm an elderly member's consent at the next visit).
CREATE TABLE chat_added_members (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  caregiver_patient_id uuid NOT NULL REFERENCES patients(id),
  member_patient_id    uuid NOT NULL REFERENCES patients(id),
  relation             text NOT NULL CHECK (relation IN ('child', 'parent', 'other')),
  proxy_scope          text NOT NULL CHECK (proxy_scope IN ('GUARDIAN', 'BOOK_ONLY', 'NONE')),
  proxy_status         text NOT NULL CHECK (proxy_status IN ('guardian_consented', 'awaiting_consent', 'pending_desk_confirmation')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, caregiver_patient_id, member_patient_id)
);
CREATE INDEX idx_chat_added_members_caregiver ON chat_added_members (tenant_id, caregiver_patient_id);
SELECT enforce_tenant_rls('chat_added_members');

CREATE TABLE patient_desk_tasks (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  kind                 text NOT NULL CHECK (kind IN ('confirm_assisted_consent')),
  patient_id           uuid NOT NULL REFERENCES patients(id),
  caregiver_patient_id uuid REFERENCES patients(id),
  status               text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  done_at              timestamptz
);
CREATE INDEX idx_patient_desk_tasks_open ON patient_desk_tasks (tenant_id, created_at) WHERE status = 'open';
SELECT enforce_tenant_rls('patient_desk_tasks');
