SET lock_timeout = '5s';
-- PHL-019: the pack barcode (GTIN-8/12/13/14, digits only) of a pharmacy item, for the counter sale's scan-to-add. One item per
-- code in a clinic, so a scan never has two answers. NULL = no barcode on file.
ALTER TABLE pharmacy_item_profiles ADD COLUMN gtin text CHECK (gtin ~ '^[0-9]{8,14}$');
-- migration-lint: allow MIG-001 pharmacy_item_profiles holds at most a few thousand rows per clinic; the partial index builds in milliseconds
CREATE UNIQUE INDEX uq_pharmacy_item_profiles_gtin ON pharmacy_item_profiles (tenant_id, gtin) WHERE gtin IS NOT NULL;
