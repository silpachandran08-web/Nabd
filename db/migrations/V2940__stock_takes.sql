SET lock_timeout = '5s';
-- PHL-010: stock take by batch (the stock_batches design that dispensing / FEFO use; goods_receipt_lines are not touched).
-- A session freezes each batch's quantity and the last movement seq of that batch. A count records the batch's last movement seq
-- at the moment it is entered, so expected = snapshot qty + the movements between the two seqs: a sale during the count is
-- never a variance. seq is assigned after the item row lock (V51), so per item it follows commit order.
CREATE TABLE stock_takes (
  id          uuid PRIMARY KEY DEFAULT uuid_v7(),
  tenant_id   uuid NOT NULL REFERENCES tenants (id),
  scope       text NOT NULL CHECK (scope IN ('all', 'location', 'class')),
  scope_value text,                       -- the location, or the schedule class; NULL for 'all'
  status      text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'posted', 'cancelled')),
  snapshot_at timestamptz NOT NULL DEFAULT now(),
  created_by  uuid NOT NULL REFERENCES staff (id),
  posted_by   uuid REFERENCES staff (id),
  approved_by uuid REFERENCES staff (id), -- the second sign-off when a controlled batch had a variance
  posted_at   timestamptz,
  CHECK ((scope = 'all') = (scope_value IS NULL)),
  CHECK (approved_by IS NULL OR approved_by <> posted_by)
);
-- one open session per clinic, so two counts never post the same batch twice
CREATE UNIQUE INDEX uq_stock_takes_open ON stock_takes (tenant_id) WHERE status = 'open';
CREATE INDEX idx_stock_takes_recent ON stock_takes (tenant_id, snapshot_at DESC);
SELECT enforce_tenant_rls('stock_takes');

CREATE TABLE stock_take_counts (
  stock_take_id          uuid NOT NULL REFERENCES stock_takes (id),
  tenant_id              uuid NOT NULL REFERENCES tenants (id),
  batch_id               uuid NOT NULL REFERENCES stock_batches (id),
  item_id                uuid NOT NULL REFERENCES charge_catalogue (id),
  system_qty_at_snapshot integer NOT NULL CHECK (system_qty_at_snapshot >= 0),
  snapshot_seq           bigint NOT NULL,  -- last movement seq of the batch at the snapshot (0 = none)
  counted_qty            integer CHECK (counted_qty >= 0),
  counted_seq            bigint,           -- last movement seq of the batch when the count was entered
  counted_by             uuid REFERENCES staff (id),
  counted_at             timestamptz,
  reason_code            text CHECK (reason_code IN ('damaged', 'count_correction', 'theft_loss', 'expired', 'other')),
  note                   text,
  posted_delta           integer,          -- the adjustment written at posting (NULL = none)
  PRIMARY KEY (stock_take_id, batch_id),
  CHECK ((counted_qty IS NULL) = (counted_seq IS NULL)),
  CHECK (reason_code <> 'other' OR btrim(COALESCE(note, '')) <> '')
);
SELECT enforce_tenant_rls('stock_take_counts');

-- A posted or cancelled session is final, and neither a session nor a count line is ever deleted (maker-checker register).
CREATE OR REPLACE FUNCTION stock_takes_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
    RAISE EXCEPTION 'stock takes are never deleted (PHL-010)';
  END IF;
  IF OLD.status <> 'open' THEN
    RAISE EXCEPTION 'stock take % is %, it cannot change (PHL-010)', OLD.id, OLD.status;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_stock_takes_guard BEFORE UPDATE OR DELETE ON stock_takes FOR EACH ROW EXECUTE FUNCTION stock_takes_guard();
CREATE TRIGGER trg_stock_takes_no_truncate BEFORE TRUNCATE ON stock_takes FOR EACH STATEMENT EXECUTE FUNCTION stock_takes_guard();

CREATE OR REPLACE FUNCTION stock_take_counts_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
    RAISE EXCEPTION 'stock take counts are never deleted (PHL-010)';
  END IF;
  -- the posting statement itself runs while the session is still open (the session is closed last, in the same transaction)
  IF NOT EXISTS (SELECT 1 FROM stock_takes s WHERE s.id = NEW.stock_take_id AND s.status = 'open') THEN
    RAISE EXCEPTION 'stock take % is closed (PHL-010)', NEW.stock_take_id;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_stock_take_counts_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_take_counts
  FOR EACH ROW EXECUTE FUNCTION stock_take_counts_guard();
CREATE TRIGGER trg_stock_take_counts_no_truncate BEFORE TRUNCATE ON stock_take_counts
  FOR EACH STATEMENT EXECUTE FUNCTION stock_take_counts_guard();

-- The adjustment register: the fixed reason code and the second signer on the (immutable) movement row; actor_id is the maker.
ALTER TABLE pharmacy_stock_movements ADD COLUMN reason_code text, ADD COLUMN approved_by uuid REFERENCES staff (id);
ALTER TABLE pharmacy_stock_movements ADD CONSTRAINT pharmacy_stock_movements_reason_code_check
  CHECK (reason_code IN ('damaged', 'count_correction', 'theft_loss', 'expired', 'other')) NOT VALID;
ALTER TABLE pharmacy_stock_movements VALIDATE CONSTRAINT pharmacy_stock_movements_reason_code_check;
