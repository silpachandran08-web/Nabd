SET lock_timeout = '5s';
-- FIN-014: invoice list and dues list indexes, reminder ledger, write-offs.
-- migration-lint: allow MIG-001 new index on the invoice list; CONCURRENTLY cannot run in the Flyway transaction (V150 precedent)
CREATE INDEX idx_invoices_tenant_status_created ON invoices (tenant_id, status, created_at DESC, id DESC);
-- migration-lint: allow MIG-001 partial index on the few open invoices; CONCURRENTLY cannot run in the Flyway transaction
CREATE INDEX idx_invoices_open_by_patient ON invoices (tenant_id, patient_id) WHERE status IN ('unpaid','partial');

CREATE TABLE dues_reminders (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  patient_id  uuid NOT NULL REFERENCES patients(id),
  invoice_ids uuid[] NOT NULL,
  channel     text NOT NULL CHECK (channel IN ('whatsapp','sms')),
  sent_by     uuid REFERENCES staff(id),
  message_ref text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dues_reminders_patient ON dues_reminders (tenant_id, patient_id, created_at DESC);
SELECT enforce_tenant_rls('dues_reminders');

-- A write-off is a ledger row (never an edit of the invoice): balance due = total - paid - written off.
CREATE TABLE invoice_write_offs (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  invoice_id  uuid NOT NULL REFERENCES invoices(id),
  amount      numeric(12,2) NOT NULL CHECK (amount > 0),
  reason_code text NOT NULL,
  note        text,
  written_by  uuid NOT NULL REFERENCES staff(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoice_write_offs_invoice ON invoice_write_offs (tenant_id, invoice_id);
SELECT enforce_tenant_rls('invoice_write_offs');
