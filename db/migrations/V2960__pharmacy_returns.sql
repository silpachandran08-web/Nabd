SET lock_timeout = '5s';
-- PHL-011: customer returns at the counter. The item flags say what may come back into stock (cold-chain items and items the
-- clinic marks not returnable never do; controlled classes are refused by their statutory register, PHL-002). The invoice line
-- remembers the batch it was sold from, so a return goes back to that batch. Expand only.
ALTER TABLE pharmacy_item_profiles
  ADD COLUMN returnable boolean NOT NULL DEFAULT true,
  ADD COLUMN cold_chain boolean NOT NULL DEFAULT false;

-- NULL = not a stocked item, sold before PHL-011, or drawn from several batches (the per-batch dispense movements keep that split).
ALTER TABLE invoice_line_items ADD COLUMN batch_id uuid REFERENCES stock_batches (id);
