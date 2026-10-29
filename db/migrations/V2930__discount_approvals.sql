SET lock_timeout = '5s';
-- FIN-018: discounts and below-list overrides above the owner's threshold wait for owner approval (in app or by the
-- owner's step-up PIN at the desk). The approval row is the waiting bill: it keeps the cart the desk sent (codes,
-- quantities, overrides, discounts; no patient data) until the desk issues the bill with it. Money is minor units.
CREATE TABLE discount_approvals (
  id           uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id    uuid NOT NULL REFERENCES tenants(id),
  invoice_id   uuid REFERENCES invoices(id),          -- the invoice it was used on (or that it extends)
  draft_ref    uuid,                                   -- the bill being drafted: the queue entry for a visit bill
  requested_by uuid NOT NULL REFERENCES staff(id),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0), -- total price reduction on the bill
  percent      numeric(5,2) CHECK (percent >= 0 AND percent <= 100), -- of the list-price subtotal
  currency     char(3) NOT NULL,
  reason_code  text NOT NULL,
  cart         jsonb NOT NULL,
  status       text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
  approver_id  uuid REFERENCES staff(id),
  method       text CHECK (method IN ('in_app', 'pin')),
  decided_at   timestamptz,
  consumed_at  timestamptz,                            -- the bill was issued with this approval (once)
  pay_later_at timestamptz,                            -- the patient left; the bill is issued once approved
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  CHECK (invoice_id IS NOT NULL OR draft_ref IS NOT NULL),
  CHECK ((status IN ('approved', 'rejected')) = (approver_id IS NOT NULL AND method IS NOT NULL AND decided_at IS NOT NULL)),
  CHECK (consumed_at IS NULL OR status = 'approved')
);
CREATE INDEX idx_discount_approvals_status ON discount_approvals (tenant_id, status, created_at);
CREATE INDEX idx_discount_approvals_draft ON discount_approvals (tenant_id, draft_ref, created_at);
CREATE INDEX idx_discount_approvals_invoice ON discount_approvals (tenant_id, invoice_id);
SELECT enforce_tenant_rls('discount_approvals');

-- invoice_discounts.approval_id (V403) now points at the approval a discount was issued under.
ALTER TABLE invoice_discounts ADD CONSTRAINT fk_invoice_discounts_approval FOREIGN KEY (approval_id) REFERENCES discount_approvals(id) NOT VALID;
ALTER TABLE invoice_discounts VALIDATE CONSTRAINT fk_invoice_discounts_approval;

-- Same key list as V2860 plus the two thresholds (minor units / percent of the bill; 'off' or no row = no threshold).
ALTER TABLE clinic_policies DROP CONSTRAINT clinic_policies_policy_key_check;
ALTER TABLE clinic_policies ADD CONSTRAINT clinic_policies_policy_key_check CHECK (policy_key IN (
  'cancellation_window_hours',
  'no_show_fee_amount',
  'reminder_hours_before',
  'refund_rule',
  'appointment_buffer_minutes',
  'session.idle_lock_minutes',
  'cash_variance_tolerance',
  'refund_owner_threshold',
  'follow_up_window_days',
  'discount_threshold_amount',
  'discount_threshold_percent',
  'print.default_paper.token_slip',
  'print.default_paper.rx',
  'print.default_paper.document',
  'print.default_paper.invoice',
  'print.default_paper.receipt'
)) NOT VALID;
ALTER TABLE clinic_policies VALIDATE CONSTRAINT clinic_policies_policy_key_check;

-- The ACC-007 coded reason list gets a 'discount' kind (price overrides, free-text charges, discounts): the codes billing
-- has accepted since FIN-003. Labels need native review before go-live, like V2711. 'other' needs no note here: billing
-- keeps its own optional note on the discount or override row.
ALTER TABLE reason_code_catalogue DROP CONSTRAINT reason_code_catalogue_kind_check;
ALTER TABLE reason_code_catalogue ADD CONSTRAINT reason_code_catalogue_kind_check
  CHECK (kind IN ('cancel','no_show','reschedule','override','refusal','discount')) NOT VALID;
ALTER TABLE reason_code_catalogue VALIDATE CONSTRAINT reason_code_catalogue_kind_check;
ALTER TABLE tenant_reason_codes DROP CONSTRAINT tenant_reason_codes_kind_check;
ALTER TABLE tenant_reason_codes ADD CONSTRAINT tenant_reason_codes_kind_check
  CHECK (kind IN ('cancel','no_show','reschedule','override','refusal','discount')) NOT VALID;
ALTER TABLE tenant_reason_codes VALIDATE CONSTRAINT tenant_reason_codes_kind_check;

WITH labels(code, sort, l) AS (VALUES
  ('goodwill',      1, '{"en":"Goodwill","hi":"सद्भावना","kn":"ಸದ್ಭಾವನೆ","te":"సద్భావన","ar":"حسن نية"}'::jsonb),
  ('price_match',   2, '{"en":"Price match","hi":"मूल्य मिलान","kn":"ಬೆಲೆ ಹೊಂದಾಣಿಕೆ","te":"ధర సరిపోలిక","ar":"مطابقة السعر"}'::jsonb),
  ('pricing_error', 3, '{"en":"Pricing error","hi":"मूल्य त्रुटि","kn":"ಬೆಲೆ ದೋಷ","te":"ధర లోపం","ar":"خطأ في السعر"}'::jsonb),
  ('staff',         4, '{"en":"Staff","hi":"स्टाफ़","kn":"ಸಿಬ್ಬಂದಿ","te":"సిబ్బంది","ar":"موظفون"}'::jsonb),
  ('waiver',        5, '{"en":"Waiver","hi":"छूट","kn":"ಮನ್ನಾ","te":"మాఫీ","ar":"إعفاء"}'::jsonb),
  ('other',         6, '{"en":"Other","hi":"अन्य","kn":"ಇತರೆ","te":"ఇతర","ar":"أخرى"}'::jsonb)
)
INSERT INTO reason_code_catalogue (kind, code, market, labels, requires_note, sort)
SELECT 'discount', l.code, m.market, l.l, false, l.sort
FROM labels l CROSS JOIN (VALUES ('IN'),('AE'),('SA'),('QA')) AS m(market);
