-- E15 Billing & Day Close: one row per clinic day that has been closed. Closing records the cash
-- count against what the day's cash payments say should be in the drawer; while a day is closed,
-- billing writes for it (new bills, payments) are refused (CheckoutService.requireDayOpen). The
-- owner can reopen it with a reason — the row flips to 'reopened' and can be closed again later.
-- Every close and reopen is also written to audit_log, which keeps the full history.
CREATE TABLE day_closes (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL REFERENCES tenants(id),
  close_date     date NOT NULL,                  -- the clinic's calendar day (ClinicClock), not UTC
  status         text NOT NULL CHECK (status IN ('closed', 'reopened')),
  expected_cash  numeric(12,2) NOT NULL,
  counted_cash   numeric(12,2) NOT NULL CHECK (counted_cash >= 0),
  variance       numeric(12,2) NOT NULL,         -- counted − expected
  note           text,                           -- required when variance is beyond tolerance
  closed_by      uuid NOT NULL REFERENCES staff(id),
  closed_at      timestamptz NOT NULL DEFAULT now(),
  reopened_by    uuid REFERENCES staff(id),
  reopened_at    timestamptz,
  reopen_reason  text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, close_date),
  CHECK ((status = 'reopened') = (reopened_at IS NOT NULL))
);
CREATE TRIGGER trg_day_closes_updated_at BEFORE UPDATE ON day_closes
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE day_closes ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON day_closes
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid);
