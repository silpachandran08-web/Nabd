-- FIN-006 (GST rule 46, MF-47): invoice, credit-note and receipt numbers come from invoice_series, one row per tenant (branch) +
-- kind + fiscal year, allocated by UPDATE ... RETURNING inside the committing transaction (row lock = gapless; a rollback consumes nothing).
-- Expand only: invoices.invoice_number keeps its legacy DEFAULT (rows written by old code / fixtures), the app now supplies the number.
SET lock_timeout = '5s';

ALTER TABLE invoice_series
  ADD COLUMN IF NOT EXISTS fy_start_month int NOT NULL DEFAULT 4 CHECK (fy_start_month BETWEEN 1 AND 12),
  ADD COLUMN IF NOT EXISTS prefix_pattern text,
  ADD COLUMN IF NOT EXISTS pad int NOT NULL DEFAULT 6 CHECK (pad BETWEEN 1 AND 12);
-- most facilities have no GST/VAT registration on file (V41); their numbering must still be gapless.
ALTER TABLE invoice_series ALTER COLUMN tax_registration_id DROP NOT NULL;

ALTER TABLE invoices
  ADD COLUMN IF NOT EXISTS invoice_series_id uuid REFERENCES invoice_series(id),
  ADD COLUMN IF NOT EXISTS series_number bigint;
-- no CREATE INDEX CONCURRENTLY in Flyway; the new columns are empty at this point
ALTER TABLE invoices ADD CONSTRAINT invoices_series_number_uniq UNIQUE (tenant_id, invoice_series_id, series_number);

ALTER TABLE invoice_payments
  ADD COLUMN IF NOT EXISTS receipt_number text;
ALTER TABLE invoice_payments ADD CONSTRAINT invoice_payments_receipt_number_uniq UNIQUE (tenant_id, receipt_number);

SELECT enforce_tenant_rls('invoice_series');
