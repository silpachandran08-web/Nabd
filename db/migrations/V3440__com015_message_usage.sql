SET lock_timeout = '5s';
-- COM-015 (CEO-03, DEC-35 open): the WhatsApp message allowance is plan data, pinned with the plan version like the seat and
-- branch limits. NULL = the plan has no allowance (cost is shown without a bar); no value is seeded while DEC-35 is open.
ALTER TABLE master.plan_versions ADD COLUMN whatsapp_message_allowance integer CHECK (whatsapp_message_allowance > 0);

-- The owner's in-app alert: one row per clinic, calendar month and threshold, ever (the primary key is the "once" rule).
-- Counts only. Raised by MessageUsageService when the Comms Gateway reports a day (comms.usage.reported.v1).
CREATE TABLE message_usage_alerts (
  tenant_id     uuid NOT NULL REFERENCES tenants (id),
  month         date NOT NULL CHECK (extract(day FROM month) = 1),
  threshold_pct int  NOT NULL CHECK (threshold_pct IN (80, 100)),
  used          bigint NOT NULL,
  allowance     bigint NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, month, threshold_pct)
);
SELECT enforce_tenant_rls('message_usage_alerts');
