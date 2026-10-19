-- FIN-001: payments are atomic and invoice status always follows paid vs total.
-- Expand-only. 'void' (V98) stays as the existing cancelled state; 'cancelled' and 'refunded' are added for the refund stories.
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check
  CHECK (status IN ('unpaid', 'partial', 'paid', 'void', 'cancelled', 'refunded'));
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0;

ALTER TABLE invoice_payments ADD COLUMN IF NOT EXISTS reference text;
ALTER TABLE invoice_payments ADD COLUMN IF NOT EXISTS idempotency_key text;
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_payments_idem
  ON invoice_payments (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Backfill paid and status from the payment rows. The migrator is subject to FORCE RLS (V211), so run as nabd_definer when available.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    SET LOCAL ROLE nabd_definer;
  END IF;
  UPDATE invoices i SET paid = s.total_paid
    FROM (SELECT invoice_id, SUM(amount) AS total_paid FROM invoice_payments GROUP BY invoice_id) s
   WHERE s.invoice_id = i.id AND i.paid <> s.total_paid;
  UPDATE invoices SET status = CASE WHEN paid >= total AND total > 0 THEN 'paid' WHEN paid > 0 THEN 'partial' ELSE 'unpaid' END
   WHERE status IN ('unpaid', 'partial', 'paid')
     AND status <> CASE WHEN paid >= total AND total > 0 THEN 'paid' WHEN paid > 0 THEN 'partial' ELSE 'unpaid' END;
END $$;
RESET ROLE;

-- NOT VALID: legacy rows that were overpaid before this fix are left alone; every new write is checked.
ALTER TABLE invoices ADD CONSTRAINT invoices_paid_bounds CHECK (paid >= 0 AND paid <= total + 0.005) NOT VALID;

-- invoice_payments already has FORCE + per-command policies from V211; re-assert so this migration is self-contained.
SELECT enforce_tenant_rls('invoice_payments');
