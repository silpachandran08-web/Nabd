SET lock_timeout = '5s';
-- FIN-025: a package sale is invoiced per component at its allocated share with the component's own tax, and a refund reverses
-- tax line by line. Tax treatment of a mixed bundle is NEEDS TAX OPINION (CEO-07 GST, CEO-08 KSA VAT point): the split is behind
-- package_settings.tax_split_enabled, which a GST clinic cannot turn on before gst_opinion_decision_id is recorded.

-- Component -> price-list head: the head's tax class and SAC/HSN decide the line's tax; tax_rate_percent stays as the fallback.
ALTER TABLE package_items ADD COLUMN IF NOT EXISTS charge_catalogue_id uuid REFERENCES charge_catalogue(id);

-- What the invoice line of a sold component carries: its taxable value and tax in minor units, and the tax class.
ALTER TABLE package_instance_items
  ADD COLUMN IF NOT EXISTS invoice_line_id uuid,
  ADD COLUMN IF NOT EXISTS allocated_minor bigint,
  ADD COLUMN IF NOT EXISTS tax_minor bigint,
  ADD COLUMN IF NOT EXISTS tax_category text;

-- The price basis the package was sold on (list prices and the bundle price share it); refunds value used sessions on the same basis.
ALTER TABLE package_instances ADD COLUMN IF NOT EXISTS tax_inclusive boolean NOT NULL DEFAULT false;

ALTER TABLE package_settings
  ADD COLUMN IF NOT EXISTS tax_split_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS gst_opinion_decision_id text;

-- Backfill. RLS is forced and the migrator has no BYPASSRLS, so scope each tenant the way the API does (as V1290).
DO $$
DECLARE t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    -- Head by name, where exactly one head of the clinic has that name; the rest stay NULL (the owner links them in the package form).
    UPDATE package_items pi SET charge_catalogue_id = c.id
      FROM charge_catalogue c
     WHERE c.tenant_id = pi.tenant_id AND lower(c.name) = lower(pi.name) AND pi.charge_catalogue_id IS NULL
       AND (SELECT count(*) FROM charge_catalogue c2 WHERE c2.tenant_id = pi.tenant_id AND lower(c2.name) = lower(pi.name)) = 1;
    -- Packages sold before this migration: one invoice line per item, charge code PKGI-<first 8 of the item id>.
    UPDATE package_instance_items ii SET invoice_line_id = li.id, allocated_minor = li.taxable_minor, tax_category = li.tax_category,
           tax_minor = COALESCE((SELECT SUM(x.amount_minor) FROM invoice_line_taxes x WHERE x.tenant_id = li.tenant_id AND x.line_id = li.id), 0)
      FROM package_instances i, invoice_line_items li
     WHERE i.id = ii.instance_id AND li.tenant_id = ii.tenant_id AND li.invoice_id = i.invoice_id
       AND li.charge_code = 'PKGI-' || upper(substr(ii.id::text, 1, 8)) AND li.taxable_minor IS NOT NULL AND ii.invoice_line_id IS NULL;
    UPDATE package_instances i SET tax_inclusive = true FROM packages p WHERE p.id = i.package_id AND p.tax_inclusive;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
