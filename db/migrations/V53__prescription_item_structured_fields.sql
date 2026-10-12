-- V53 (DOC-033): structured prescription pad. Additive and nullable so older clients (five free-text fields) keep working.
--   brand_name    the brand/product text the doctor typed or picked; the safety checks run on drug_name AND brand_name.
--   sig_pattern   the structured morning-noon-night pattern as typed ("1-0-1"); `frequency` stays the free-text sig.
--   route         free text from a fixed UI list of generic routes (oral, topical, ...); the server does not interpret it.
--   quantity / quantity_unit  what to dispense. Entered or confirmed by the doctor, never derived server-side.
-- No drug formulary is created here: suggestions come from the tenant's own pharmacy item master (charge_catalogue)
-- and previously prescribed names.
--
-- Signed-Rx immutability (V51): trg_prescription_items_signed_lock is a row-level BEFORE INSERT/UPDATE/DELETE trigger
-- with no column list, and the header trigger blocks any UPDATE of a signed prescription, so the new columns are
-- covered as-is. The trigger functions are therefore NOT redefined here.
ALTER TABLE prescription_items ADD COLUMN IF NOT EXISTS brand_name text;
ALTER TABLE prescription_items ADD COLUMN IF NOT EXISTS sig_pattern text;
ALTER TABLE prescription_items ADD COLUMN IF NOT EXISTS route text;
ALTER TABLE prescription_items ADD COLUMN IF NOT EXISTS quantity numeric(8,2) CHECK (quantity IS NULL OR quantity > 0);
ALTER TABLE prescription_items ADD COLUMN IF NOT EXISTS quantity_unit text;

-- Fast type-ahead over the tenant's prescribing history (distinct names).
CREATE INDEX IF NOT EXISTS idx_prescription_items_tenant_drug ON prescription_items (tenant_id, lower(drug_name));
