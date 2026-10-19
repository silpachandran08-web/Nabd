-- FIN-003: server-side line pricing provenance + discount ledger (invoices.discount becomes SUM of the ledger).
ALTER TABLE invoice_line_items
  ADD COLUMN IF NOT EXISTS charge_catalogue_id uuid REFERENCES charge_catalogue(id),
  ADD COLUMN IF NOT EXISTS list_unit_price numeric(12,2),
  ADD COLUMN IF NOT EXISTS tier text CHECK (tier IN ('normal','follow_up','emergency','custom')),
  ADD COLUMN IF NOT EXISTS override_reason_code text,
  ADD COLUMN IF NOT EXISTS override_note text,
  ADD COLUMN IF NOT EXISTS overridden_by uuid REFERENCES staff(id);
UPDATE invoice_line_items SET list_unit_price = unit_price WHERE list_unit_price IS NULL;

CREATE TABLE IF NOT EXISTS invoice_discounts (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  invoice_id  uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  line_id     uuid REFERENCES invoice_line_items(id) ON DELETE CASCADE,
  kind        text NOT NULL CHECK (kind IN ('amount','percent')),
  value       numeric(12,2) NOT NULL,
  amount      numeric(12,2) NOT NULL CHECK (amount > 0),
  reason_code text NOT NULL,
  note        text,
  applied_by  uuid NOT NULL REFERENCES staff(id),
  approval_id uuid,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_invoice_discounts_invoice ON invoice_discounts (tenant_id, invoice_id);

-- one legacy row per invoice that already carries a discount, so SUM(ledger) = invoices.discount holds for old data too
INSERT INTO invoice_discounts (tenant_id, invoice_id, kind, value, amount, reason_code, applied_by)
SELECT i.tenant_id, i.id, 'amount', i.discount, i.discount, 'legacy_unspecified', i.created_by
FROM invoices i
WHERE i.discount > 0 AND NOT EXISTS (SELECT 1 FROM invoice_discounts d WHERE d.invoice_id = i.id);

SELECT enforce_tenant_rls('invoice_discounts');
