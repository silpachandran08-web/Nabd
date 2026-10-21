-- FIN-006: every monetary header carries currency char(3) and *_minor bigint (amount x 10^exponent(currency)), so KWD/BHD/OMR work.
-- Dual-write phase: BEFORE triggers fill currency (from the tenant's region) and every *_minor from the numeric column, so no writer
-- can forget one; reads switch to *_minor in a later release, then the numeric columns are contracted.
-- Region -> currency lives here until OPS-061 (organization.api region reference data) replaces it.
SET lock_timeout = '5s';

CREATE OR REPLACE FUNCTION currency_exponent(cur text) RETURNS int LANGUAGE sql IMMUTABLE AS
$$ SELECT CASE cur WHEN 'KWD' THEN 3 WHEN 'BHD' THEN 3 WHEN 'OMR' THEN 3 WHEN 'JOD' THEN 3 WHEN 'TND' THEN 3 WHEN 'JPY' THEN 0 ELSE 2 END $$;

CREATE OR REPLACE FUNCTION tenant_currency(t uuid) RETURNS char(3) LANGUAGE sql STABLE AS
$$ SELECT CASE region WHEN 'IN' THEN 'INR' WHEN 'KSA' THEN 'SAR' WHEN 'AE' THEN 'AED' WHEN 'QA' THEN 'QAR' END::char(3)
     FROM tenants WHERE id = t $$;

CREATE OR REPLACE FUNCTION to_minor(amount numeric, cur text) RETURNS bigint LANGUAGE sql IMMUTABLE AS
$$ SELECT round(amount * power(10::numeric, currency_exponent(cur)))::bigint $$;

-- one trigger function per table (columns differ); each fills currency when absent and refuses a tenant without a known region
CREATE OR REPLACE FUNCTION money_sync_invoices() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.subtotal_minor := to_minor(NEW.subtotal, NEW.currency);
  NEW.discount_minor := to_minor(NEW.discount, NEW.currency);
  NEW.tax_minor := to_minor(NEW.tax, NEW.currency);
  NEW.round_off_minor := to_minor(NEW.round_off, NEW.currency);
  NEW.total_minor := to_minor(NEW.total, NEW.currency);
  NEW.paid_minor := to_minor(NEW.paid, NEW.currency);
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION money_sync_invoice_payments() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.amount_minor := to_minor(NEW.amount, NEW.currency);
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION money_sync_package_instances() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.sold_price_minor := to_minor(NEW.sold_price, NEW.currency);
  NEW.sold_tax_minor := to_minor(NEW.sold_tax, NEW.currency);
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION money_sync_package_refunds() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.used_list_value_minor := to_minor(NEW.used_list_value, NEW.currency);
  NEW.refund_amount_minor := to_minor(NEW.refund_amount, NEW.currency);
  NEW.amount_owed_minor := to_minor(NEW.amount_owed, NEW.currency);
  NEW.recomputed_amount_minor := to_minor(NEW.recomputed_amount, NEW.currency);
  RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION money_sync_day_closes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.currency := COALESCE(NEW.currency, tenant_currency(NEW.tenant_id));
  IF NEW.currency IS NULL THEN RAISE EXCEPTION 'no currency for tenant %', NEW.tenant_id; END IF;
  NEW.expected_cash_minor := to_minor(NEW.expected_cash, NEW.currency);
  NEW.counted_cash_minor := to_minor(NEW.counted_cash, NEW.currency);
  NEW.variance_minor := to_minor(NEW.variance, NEW.currency);
  RETURN NEW;
END $$;

ALTER TABLE invoices
  ADD COLUMN IF NOT EXISTS currency char(3), ADD COLUMN IF NOT EXISTS subtotal_minor bigint, ADD COLUMN IF NOT EXISTS discount_minor bigint,
  ADD COLUMN IF NOT EXISTS tax_minor bigint, ADD COLUMN IF NOT EXISTS round_off_minor bigint, ADD COLUMN IF NOT EXISTS total_minor bigint,
  ADD COLUMN IF NOT EXISTS paid_minor bigint;
ALTER TABLE invoice_payments ADD COLUMN IF NOT EXISTS currency char(3), ADD COLUMN IF NOT EXISTS amount_minor bigint;
ALTER TABLE package_instances ADD COLUMN IF NOT EXISTS currency char(3), ADD COLUMN IF NOT EXISTS sold_price_minor bigint,
  ADD COLUMN IF NOT EXISTS sold_tax_minor bigint;
ALTER TABLE package_refunds ADD COLUMN IF NOT EXISTS currency char(3), ADD COLUMN IF NOT EXISTS used_list_value_minor bigint,
  ADD COLUMN IF NOT EXISTS refund_amount_minor bigint, ADD COLUMN IF NOT EXISTS amount_owed_minor bigint,
  ADD COLUMN IF NOT EXISTS recomputed_amount_minor bigint;
ALTER TABLE day_closes ADD COLUMN IF NOT EXISTS currency char(3), ADD COLUMN IF NOT EXISTS expected_cash_minor bigint,
  ADD COLUMN IF NOT EXISTS counted_cash_minor bigint, ADD COLUMN IF NOT EXISTS variance_minor bigint;

CREATE TRIGGER trg_money_sync BEFORE INSERT OR UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION money_sync_invoices();
CREATE TRIGGER trg_money_sync BEFORE INSERT OR UPDATE ON invoice_payments FOR EACH ROW EXECUTE FUNCTION money_sync_invoice_payments();
CREATE TRIGGER trg_money_sync BEFORE INSERT OR UPDATE ON package_instances FOR EACH ROW EXECUTE FUNCTION money_sync_package_instances();
CREATE TRIGGER trg_money_sync BEFORE INSERT OR UPDATE ON package_refunds FOR EACH ROW EXECUTE FUNCTION money_sync_package_refunds();
CREATE TRIGGER trg_money_sync BEFORE INSERT OR UPDATE ON day_closes FOR EACH ROW EXECUTE FUNCTION money_sync_day_closes();

-- Backfill (FORCE RLS applies to the migrator, so as nabd_definer where it exists, V211 rule). The no-op UPDATE fires the trigger.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nabd_definer') AND pg_has_role(current_user, 'nabd_definer', 'MEMBER') THEN
    SET LOCAL ROLE nabd_definer;
  END IF;
  UPDATE invoices SET currency = tenant_currency(tenant_id) WHERE currency IS NULL;
  UPDATE invoice_payments SET currency = tenant_currency(tenant_id) WHERE currency IS NULL;
  UPDATE package_instances SET currency = tenant_currency(tenant_id) WHERE currency IS NULL;
  UPDATE package_refunds SET currency = tenant_currency(tenant_id) WHERE currency IS NULL;
  UPDATE day_closes SET currency = tenant_currency(tenant_id) WHERE currency IS NULL;
END $$;
RESET ROLE;

-- currency is non-null from here on (validated CHECK first so SET NOT NULL does not rescan under a long lock, MIG-003)
ALTER TABLE invoices ADD CONSTRAINT invoices_currency_nn CHECK (currency IS NOT NULL) NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_currency_nn;
ALTER TABLE invoices ALTER COLUMN currency SET NOT NULL;
ALTER TABLE invoice_payments ADD CONSTRAINT invoice_payments_currency_nn CHECK (currency IS NOT NULL) NOT VALID;
ALTER TABLE invoice_payments VALIDATE CONSTRAINT invoice_payments_currency_nn;
ALTER TABLE invoice_payments ALTER COLUMN currency SET NOT NULL;
ALTER TABLE package_instances ADD CONSTRAINT package_instances_currency_nn CHECK (currency IS NOT NULL) NOT VALID;
ALTER TABLE package_instances VALIDATE CONSTRAINT package_instances_currency_nn;
ALTER TABLE package_instances ALTER COLUMN currency SET NOT NULL;
ALTER TABLE package_refunds ADD CONSTRAINT package_refunds_currency_nn CHECK (currency IS NOT NULL) NOT VALID;
ALTER TABLE package_refunds VALIDATE CONSTRAINT package_refunds_currency_nn;
ALTER TABLE package_refunds ALTER COLUMN currency SET NOT NULL;
ALTER TABLE day_closes ADD CONSTRAINT day_closes_currency_nn CHECK (currency IS NOT NULL) NOT VALID;
ALTER TABLE day_closes VALIDATE CONSTRAINT day_closes_currency_nn;
ALTER TABLE day_closes ALTER COLUMN currency SET NOT NULL;
