SET lock_timeout = '5s';
-- ADM-006: the owner books a paid one-time white-glove setup from the wizard. The fee is plan catalogue data per market (each
-- master.plans row is sold in one market and currency), never a constant in code; NULL = not offered on that plan.
ALTER TABLE master.plans ADD COLUMN white_glove_fee_minor bigint CHECK (white_glove_fee_minor >= 0);
-- CEO-21 / SCS-04 list prices (Rs 4,999 / SAR 499), net of tax. NEEDS COMMERCIAL CHECK before go-live: AED and QAR plans get no
-- fee here (not offered until commercial sets one in the plan catalogue).
UPDATE master.plans SET white_glove_fee_minor = 499900 WHERE currency = 'INR';
UPDATE master.plans SET white_glove_fee_minor = 49900 WHERE currency = 'SAR';

-- The billing period of the subscription: the fee is waived on annual plans. Existing subscriptions are monthly.
ALTER TABLE master.subscriptions ADD COLUMN billing_period text NOT NULL DEFAULT 'monthly' CHECK (billing_period IN ('monthly','annual'));

-- The due fee is a Nabd invoice like ADM-037's proration (the billing gateway issues the legal tax document and takes payment).
ALTER TABLE master.platform_invoices DROP CONSTRAINT platform_invoices_kind_check;
ALTER TABLE master.platform_invoices ADD CONSTRAINT platform_invoices_kind_check CHECK (kind IN ('proration','renewal','white_glove')) NOT VALID;
ALTER TABLE master.platform_invoices VALIDATE CONSTRAINT platform_invoices_kind_check;

-- One engagement per booking on the existing tenant (not a new tenant). No PHI: the owner's note is pattern-redacted before it is
-- stored (TicketPhiRedactor). step_up_id = the step-up token's jti, unique: one MFA confirmation books once. ticket_id = the system
-- support ticket the onboarding operator names when asking the owner for a support-access grant (PLT-057); the operator never
-- has standing access to the clinic.
CREATE TABLE master.white_glove_engagements (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL REFERENCES tenants(id),
  status             text NOT NULL DEFAULT 'requested' CHECK (status IN ('requested','scheduled','in_progress','done','cancelled')),
  fee_minor          bigint NOT NULL CHECK (fee_minor >= 0),
  currency           text NOT NULL CHECK (currency IN ('INR','SAR','AED','QAR')),
  fee_status         text NOT NULL CHECK (fee_status IN ('due','paid','waived')),
  waiver_reason      text CHECK (waiver_reason IN ('annual_plan')),
  invoice_id         uuid REFERENCES master.platform_invoices(id),
  preferred_slots    timestamptz[] NOT NULL CHECK (cardinality(preferred_slots) BETWEEN 1 AND 3),
  notes              text CHECK (length(notes) <= 500),
  slot_at            timestamptz,
  operator_id        uuid,
  ticket_id          uuid,
  covered_steps      text[],
  requested_by_staff uuid NOT NULL,
  step_up_id         uuid NOT NULL UNIQUE,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  closed_at          timestamptz,
  CHECK ((fee_status = 'waived') = (waiver_reason IS NOT NULL))
);
-- a clinic has one open booking until it is done or cancelled (409 ENGAGEMENT_OPEN)
CREATE UNIQUE INDEX uq_white_glove_open ON master.white_glove_engagements (tenant_id) WHERE status IN ('requested','scheduled','in_progress');
CREATE INDEX idx_white_glove_queue ON master.white_glove_engagements (status, created_at);
SELECT enforce_tenant_rls('master.white_glove_engagements');

-- The onboarding queue across clinics (operator console, outside any tenant scope): engagement rows and the clinic name only.
CREATE FUNCTION white_glove_queue(p_include_closed boolean)
RETURNS TABLE (id uuid, tenant_id uuid, tenant_name text, status text, fee_minor bigint, currency text, fee_status text,
               waiver_reason text, preferred_slots timestamptz[], notes text, slot_at timestamptz, operator_id uuid, ticket_id uuid,
               covered_steps text[], created_at timestamptz, closed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT e.id, e.tenant_id, t.name, e.status, e.fee_minor, e.currency, e.fee_status, e.waiver_reason, e.preferred_slots, e.notes,
         e.slot_at, e.operator_id, e.ticket_id, e.covered_steps, e.created_at, e.closed_at
  FROM master.white_glove_engagements e JOIN tenants t ON t.id = e.tenant_id
  WHERE p_include_closed OR e.status IN ('requested','scheduled','in_progress')
  ORDER BY CASE e.status WHEN 'requested' THEN 0 WHEN 'scheduled' THEN 1 WHEN 'in_progress' THEN 2 ELSE 3 END, e.created_at
  LIMIT 500;
$$;

-- Which clinic an engagement belongs to, so an operator transition runs in that clinic's tenant scope.
CREATE FUNCTION white_glove_tenant_of(p_id uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT tenant_id FROM master.white_glove_engagements WHERE id = p_id;
$$;

REVOKE ALL ON FUNCTION white_glove_queue(boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION white_glove_tenant_of(uuid) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_app') THEN
    GRANT EXECUTE ON FUNCTION white_glove_queue(boolean) TO nabd_app;
    GRANT EXECUTE ON FUNCTION white_glove_tenant_of(uuid) TO nabd_app;
  END IF;
END $$;
SELECT nabd_adopt_definer_functions();
