SET lock_timeout = '5s';
-- PHL-005: one OPENING batch per stocked item (qty = the old stock_qty, expiry unknown) and an 'opening' movement.
-- Idempotent (skips items that already have a batch) and aborts the migration if any item's batches do not add up to its stock_qty.
INSERT INTO stock_batches (tenant_id, item_id, batch_no, expiry_date, qty_on_hand)
SELECT c.tenant_id, c.id, 'OPENING', NULL, c.stock_qty
FROM charge_catalogue c
WHERE c.stock_qty IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM stock_batches b WHERE b.tenant_id = c.tenant_id AND b.item_id = c.id);

INSERT INTO pharmacy_stock_movements (tenant_id, item_id, batch_id, kind, delta, qty_before, qty_after, reason, source_type)
SELECT b.tenant_id, b.item_id, b.id, 'opening', b.qty_on_hand, 0, b.qty_on_hand, 'Opening balance (PHL-005)', 'migration'
FROM stock_batches b
WHERE b.batch_no = 'OPENING' AND b.qty_on_hand > 0
  AND NOT EXISTS (SELECT 1 FROM pharmacy_stock_movements m WHERE m.batch_id = b.id AND m.kind = 'opening');

DO $$
DECLARE bad integer;
BEGIN
  SELECT count(*) INTO bad FROM charge_catalogue c
  WHERE c.stock_qty IS NOT NULL
    AND c.stock_qty <> COALESCE((SELECT sum(b.qty_on_hand) FROM stock_batches b WHERE b.tenant_id = c.tenant_id AND b.item_id = c.id), 0);
  IF bad > 0 THEN
    RAISE EXCEPTION 'PHL-005 sum check failed for % item(s): batches do not add up to stock_qty', bad;
  END IF;
END $$;
