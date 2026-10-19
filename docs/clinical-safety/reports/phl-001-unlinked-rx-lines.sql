-- PHL-001 one-off report for the CSO (hazard H-04): historical invoice lines for a prescription-only item that are not
-- linked to a signed prescription line. Read-only. Ids, codes, quantities and dates only (no patient name or drug free text).
-- Run as a role that can read every tenant (e.g. SET ROLE nabd_definer), or per tenant after set_config('app.tenant_id', ...).
SELECT li.tenant_id, i.invoice_number, i.created_at::date AS sold_on, li.charge_code, li.quantity, li.id AS line_id
  FROM invoice_line_items li
  JOIN invoices i ON i.id = li.invoice_id
 WHERE li.is_rx AND li.prescription_item_id IS NULL
 ORDER BY li.tenant_id, i.created_at;
