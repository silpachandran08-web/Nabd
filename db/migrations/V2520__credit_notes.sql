SET lock_timeout = '5s';
-- FIN-013: refund requests (maker-checker), tax credit notes with per-line reversal, negative payment rows for refunds.
-- Numbers come from the FIN-006 credit_note series. Money is minor units only; every table is tenant scoped with forced RLS.

CREATE TABLE refund_requests (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL REFERENCES tenants(id),
  invoice_id    uuid NOT NULL REFERENCES invoices(id),
  amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
  currency      char(3) NOT NULL,
  reason_code   text NOT NULL,
  note          text,
  lines         jsonb,                      -- [{lineId, quantity, resaleable}] or null for a goodwill amount refund
  refund_to     jsonb NOT NULL,             -- [{paymentId, amountMinor}]
  status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
  requested_by  uuid NOT NULL REFERENCES staff(id),
  approved_by   uuid REFERENCES staff(id),  -- the deciding staff member (approver or rejecter)
  decision_note text,
  decided_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_refund_requests_one_pending ON refund_requests (tenant_id, invoice_id) WHERE status = 'pending';
CREATE INDEX idx_refund_requests_status ON refund_requests (tenant_id, status, created_at DESC, id);

CREATE TABLE credit_notes (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL REFERENCES tenants(id),
  invoice_id        uuid NOT NULL REFERENCES invoices(id),
  refund_request_id uuid REFERENCES refund_requests(id),
  series_id         uuid REFERENCES invoice_series(id),
  number            text NOT NULL,
  currency          char(3) NOT NULL,
  taxable_minor     bigint NOT NULL,
  tax_minor         bigint NOT NULL,
  round_off_minor   bigint NOT NULL DEFAULT 0,
  total_minor       bigint NOT NULL CHECK (total_minor > 0),
  issued_at         timestamptz NOT NULL DEFAULT now(),
  issued_by         uuid NOT NULL REFERENCES staff(id),
  UNIQUE (tenant_id, number)
);
CREATE INDEX idx_credit_notes_invoice ON credit_notes (tenant_id, invoice_id);

CREATE TABLE credit_note_lines (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  credit_note_id  uuid NOT NULL REFERENCES credit_notes(id),
  invoice_line_id uuid NOT NULL REFERENCES invoice_line_items(id),
  quantity        int NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  taxable_minor   bigint NOT NULL,
  tax_minor       bigint NOT NULL,
  taxes           jsonb NOT NULL DEFAULT '[]'::jsonb   -- [{type, ratePercent, amountMinor}] components reversed
);
CREATE INDEX idx_credit_note_lines_cn ON credit_note_lines (tenant_id, credit_note_id);
CREATE INDEX idx_credit_note_lines_line ON credit_note_lines (tenant_id, invoice_line_id);

ALTER TABLE invoice_payments
  ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'payment',
  ADD COLUMN IF NOT EXISTS refund_request_id uuid REFERENCES refund_requests(id),
  ADD COLUMN IF NOT EXISTS refund_of_payment_id uuid REFERENCES invoice_payments(id);
ALTER TABLE invoice_payments DROP CONSTRAINT IF EXISTS invoice_payments_amount_check;
ALTER TABLE invoice_payments ADD CONSTRAINT invoice_payments_kind_amount
  CHECK ((kind = 'payment' AND amount > 0) OR (kind = 'refund' AND amount < 0)) NOT VALID;
ALTER TABLE invoice_payments VALIDATE CONSTRAINT invoice_payments_kind_amount;

ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes',
  'cash_variance_tolerance',
  'refund_owner_threshold'
)) NOT VALID;
ALTER TABLE clinic_policies VALIDATE CONSTRAINT clinic_policies_policy_key_check;

SELECT enforce_tenant_rls('refund_requests');
SELECT enforce_tenant_rls('credit_notes');
SELECT enforce_tenant_rls('credit_note_lines');
