SET lock_timeout = '5s';
-- PHL-008: daily stock alerts and the one-person digest. The alert job (StockAlertJob) raises rows here for the bound clinic:
-- one per batch per expiry threshold (ever), one low-stock row per item until a receipt tops it up, one dead-stock row per
-- item until it moves again. The digest goes to pharmacy_settings.alert_recipient_staff_id (PHL-004) at digest_local_time
-- (clinic time); last_digest_day keeps it to one per clinic day. Ids, counts and amounts only.
ALTER TABLE pharmacy_settings
  ADD COLUMN expiry_alert_days int[] NOT NULL DEFAULT '{90,60,30}' CHECK (expiry_alert_days <@ '{90,60,30}'::int[]),
  ADD COLUMN dead_stock_days int NOT NULL DEFAULT 90 CHECK (dead_stock_days BETWEEN 30 AND 365),
  ADD COLUMN digest_local_time time NOT NULL DEFAULT '07:00',
  ADD COLUMN last_digest_day date;

-- On-hand below this raises a low-stock alert; NULL = no reorder level set (no alert).
ALTER TABLE pharmacy_item_profiles ADD COLUMN reorder_level int CHECK (reorder_level >= 0);

CREATE TABLE stock_alerts (
  id              uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id       uuid NOT NULL REFERENCES tenants (id),
  type            text NOT NULL CHECK (type IN ('expiry_90', 'expiry_60', 'expiry_30', 'expired', 'low_stock', 'dead_stock')),
  item_id         uuid NOT NULL REFERENCES charge_catalogue (id),
  batch_id        uuid REFERENCES stock_batches (id),
  value_at_risk   numeric(14,2),            -- qty x purchase_rate when raised (expiry: the batch; dead: the item; low: NULL)
  status          text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged')),
  action          text CHECK (action IN ('returned', 'discounted', 'quarantined', 'none')),
  note            text CHECK (length(note) <= 500),
  acknowledged_by uuid REFERENCES staff (id),
  acknowledged_at timestamptz,
  created_on      date NOT NULL,            -- clinic date the alert was raised
  created_at      timestamptz NOT NULL DEFAULT now(),
  CHECK ((type IN ('low_stock', 'dead_stock')) = (batch_id IS NULL)),
  CHECK ((status = 'acknowledged') = (action IS NOT NULL))
);
-- The spec's per-day key, plus: each expiry threshold fires once per batch, ever (a race between two runs inserts one row).
CREATE UNIQUE INDEX uq_stock_alerts_day ON stock_alerts (tenant_id, type, COALESCE(batch_id, item_id), created_on);
CREATE UNIQUE INDEX uq_stock_alerts_batch_threshold ON stock_alerts (tenant_id, type, batch_id) WHERE batch_id IS NOT NULL;
CREATE INDEX idx_stock_alerts_item ON stock_alerts (tenant_id, item_id, type, created_at DESC);
CREATE INDEX idx_stock_alerts_open ON stock_alerts (tenant_id, type, id) WHERE status = 'open';
SELECT enforce_tenant_rls('stock_alerts');

-- The digest template's slots are numbers of batches/items (never a drug or patient): a typed 'count' slot for the COM-014 lint.
INSERT INTO template_lint_rules (language, kind, pattern, source) VALUES ('*', 'allow_slot', 'count', 'PHL-008 v1')
  ON CONFLICT DO NOTHING;
