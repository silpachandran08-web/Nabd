SET lock_timeout = '5s';
-- CLN-021: prescription lines, favourite-set lines and allergies can carry drug master codes. All nullable: a free-text
-- line stays valid (shown as 'uncoded') and the dictionary text match keeps running for it. Columns are only added, the
-- V51 trigger that freezes signed rows is untouched.
ALTER TABLE prescription_items ADD COLUMN drug_id text, ADD COLUMN generic_name_snapshot text;
ALTER TABLE favourite_rx_set_items ADD COLUMN drug_id text, ADD COLUMN generic_name_snapshot text;
ALTER TABLE patient_allergies ADD COLUMN allergen_ingredient_id text, ADD COLUMN allergen_class_id text;

-- Suggestions for historical free-text lines. Side table only: the signed row is never modified.
CREATE TABLE rx_coding_suggestions (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            uuid NOT NULL REFERENCES tenants(id),
  prescription_item_id uuid NOT NULL,
  suggested_drug_id    text NOT NULL,
  confidence           numeric(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  created_at           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, prescription_item_id)
);
SELECT enforce_tenant_rls('rx_coding_suggestions');
