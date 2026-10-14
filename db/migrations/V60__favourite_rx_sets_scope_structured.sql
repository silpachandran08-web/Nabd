-- V60 (DOC-035): favourite Rx sets become editable, shareable and carry the DOC-033 structured pad fields.
--   shared         a doctor's own set that the whole clinic may see and apply (scope "shared" for other doctors).
--                  doctor_id NULL stays "clinic standard" (NB-114); it is editable only with clinical:approve.
--   updated_at     last edit; last_used_at is stamped when the set is applied to a prescription.
--   item columns   brand/sig/route/quantity as entered by the doctor. Before this, saving a set dropped them and
--                  applying a set lost them; the safety checks run on drug AND brand when a set is applied.
-- Additive and nullable: existing sets keep working. RLS already exists on both tables (V34) and covers new columns.
ALTER TABLE favourite_rx_sets ADD COLUMN IF NOT EXISTS shared boolean NOT NULL DEFAULT false;
ALTER TABLE favourite_rx_sets ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE favourite_rx_sets ADD COLUMN IF NOT EXISTS last_used_at timestamptz;

ALTER TABLE favourite_rx_set_items ADD COLUMN IF NOT EXISTS brand_name text;
ALTER TABLE favourite_rx_set_items ADD COLUMN IF NOT EXISTS sig_pattern text;
ALTER TABLE favourite_rx_set_items ADD COLUMN IF NOT EXISTS route text;
ALTER TABLE favourite_rx_set_items ADD COLUMN IF NOT EXISTS quantity numeric(8,2) CHECK (quantity IS NULL OR quantity > 0);
ALTER TABLE favourite_rx_set_items ADD COLUMN IF NOT EXISTS quantity_unit text;

CREATE INDEX IF NOT EXISTS idx_favourite_rx_sets_tenant_doctor ON favourite_rx_sets (tenant_id, doctor_id);

-- Encounter list (EncounterListRepository) counts prescription_items per prescription; V21/V53 index only tenant+drug.
CREATE INDEX IF NOT EXISTS idx_prescription_items_prescription ON prescription_items (prescription_id);
