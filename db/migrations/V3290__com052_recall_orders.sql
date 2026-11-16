SET lock_timeout = '5s';
-- COM-052: engagement module. A recall order says when a patient is due back; the doctor sets it at consult close
-- ("Review in 2 weeks"), recall rules / registers / vaccine schedules (COM-053) add the other sources later. reason_code
-- is a code, never free text: it may reach a patient message, a diagnosis must not. Booking the follow-up sets
-- status 'booked' and booked_appointment_id; the due-recall sender (COM-054) only messages 'pending' rows.
CREATE TABLE recall_orders (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id             uuid NOT NULL REFERENCES tenants(id),
  patient_id            uuid NOT NULL,
  source_encounter_id   uuid,
  doctor_id             uuid,
  rule_id               uuid,
  source                text NOT NULL CHECK (source IN ('consult', 'rule', 'register', 'vaccine')),
  reason_code           text CHECK (reason_code ~ '^[a-z0-9_.-]{1,64}$'),
  interval_days         integer CHECK (interval_days BETWEEN 1 AND 730),
  due_date              date NOT NULL,
  status                text NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending', 'messaged', 'booked', 'callback', 'done', 'cancelled')),
  messages_sent         integer NOT NULL DEFAULT 0 CHECK (messages_sent >= 0),
  last_messaged_at      timestamptz,
  booked_appointment_id uuid,
  callback_after        date,
  created_by            uuid,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT recall_orders_patient_fk FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
  CONSTRAINT recall_orders_doctor_fk FOREIGN KEY (tenant_id, doctor_id) REFERENCES staff (tenant_id, id),
  CONSTRAINT recall_orders_booked CHECK (status <> 'booked' OR booked_appointment_id IS NOT NULL)
);
CREATE INDEX idx_recall_orders_due ON recall_orders (tenant_id, status, due_date);
CREATE INDEX idx_recall_orders_patient ON recall_orders (tenant_id, patient_id);
-- one recall per visit: a second tap is 409 RECALL_EXISTS_FOR_ENCOUNTER
CREATE UNIQUE INDEX uq_recall_orders_encounter ON recall_orders (tenant_id, source_encounter_id) WHERE source_encounter_id IS NOT NULL;
SELECT enforce_tenant_rls('recall_orders');
