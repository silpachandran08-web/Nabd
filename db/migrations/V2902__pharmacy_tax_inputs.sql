SET lock_timeout = '5s';
-- PHL-021: the inputs pharmacy hands to the billing tax engine (pharmacy never computes tax itself).
--   price_inclusive_of_tax  NULL = follow the charge head's own setting; true = the price is tax-inclusive (India MRP pricing)
--   zero_rated              KSA-style zero-rated medicine: VAT 0% with the zero-rating reason on the invoice
--   hsn_code                copy of charge_catalogue.hsn_code (the old column stays until the contract step)
--   mrp                     ceiling for the selling price (per batch once PHL-005 lands; item level until then)
ALTER TABLE pharmacy_item_profiles
  ADD COLUMN price_inclusive_of_tax boolean,
  ADD COLUMN zero_rated boolean NOT NULL DEFAULT false,
  ADD COLUMN hsn_code text,
  ADD COLUMN mrp numeric(12,2) CHECK (mrp >= 0);

DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id', t.id::text, true);
    PERFORM set_config('app.visible_tenant_ids', t.id::text, true);
    UPDATE pharmacy_item_profiles p SET hsn_code = c.hsn_code
      FROM charge_catalogue c WHERE c.id = p.item_id AND c.tenant_id = p.tenant_id AND p.tenant_id = t.id AND c.hsn_code IS NOT NULL;
  END LOOP;
  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.visible_tenant_ids', '', true);
END $$;
