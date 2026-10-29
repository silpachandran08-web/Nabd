SET lock_timeout = '5s';
-- ADM-011: who pressed Go live (tenants.setup_completed_at already says when).
ALTER TABLE tenants ADD COLUMN go_live_by uuid;

-- Market-specific go-live item as data, not a region switch in code: the e-invoicing scheme a market requires on top of the tax id.
ALTER TABLE regions ADD COLUMN einvoice_scheme text;
UPDATE regions SET einvoice_scheme = 'ZATCA' WHERE code = 'SA';
