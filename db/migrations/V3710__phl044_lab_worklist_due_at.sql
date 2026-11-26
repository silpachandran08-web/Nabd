SET lock_timeout = '5s';
-- PHL-044: lab worklist with TAT-based sorting and due date computation.
-- Add due_at to lab_orders: computed on write as ordered_at + earliest test TAT.
-- For multi-test orders, due_at is the earliest (most restrictive) deadline.

ALTER TABLE lab_orders ADD COLUMN due_at timestamptz NOT NULL DEFAULT now();

-- Drop the old worklist index and create a new one with due_at.
DROP INDEX idx_lab_orders_worklist;
CREATE INDEX idx_lab_orders_worklist ON lab_orders (tenant_id, status, priority, due_at)
  WHERE status NOT IN ('released', 'cancelled');

-- Backfill due_at: for each order, find the minimum deadline from its tests' TAT.
-- TAT is stored in lab_tests.tat_minutes; use a 24h (1440 min) default if not found (conservative).
UPDATE lab_orders o SET due_at =
  o.ordered_at +
  (COALESCE((SELECT MIN(tat_minutes) FROM lab_tests t
    JOIN lab_order_items i ON i.test_id = t.id AND i.order_id = o.id
  ), 1440) || ' minutes')::interval;

-- migration-lint allow: table created this sprint (V3630)
