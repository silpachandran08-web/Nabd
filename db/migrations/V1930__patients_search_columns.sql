-- OPS-009: index-assisted patient search. Generated (not trigger) columns, so they can never drift from phone/name.
-- Stored generated columns rewrite the table once: run in the maintenance window for a large region (playbook).
SET lock_timeout = '5s';

-- Allow-list: pg_trgm (V3) and btree_gist (V312) are already in use; btree_gin is contrib in the same package as both
-- and is offered by RDS/Aurora/Cloud SQL/Azure/Neon. No provider allow-list document exists in this repo:
-- Needs external action: confirm btree_gin on the production provider before the region deploy.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- Mirrors NameNormaliser.java (shared golden file backend/src/test/resources/ops009-search-golden.tsv):
-- lower case, Arabic harakat/superscript alef/tatweel removed, alef variants -> alef, alef maqsura -> yaa,
-- taa marbuta -> haa, whitespace collapsed.
CREATE OR REPLACE FUNCTION normalise_name(s text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT btrim(regexp_replace(
           translate(regexp_replace(lower(s), '[ً-ْٰـ]', '', 'g'),
                     chr(1571) || chr(1573) || chr(1570) || chr(1649) || chr(1609) || chr(1577),
                     chr(1575) || chr(1575) || chr(1575) || chr(1575) || chr(1610) || chr(1607)),
           '\s+', ' ', 'g'))
$$;

-- National significant number digits. shortcut: calling codes hard-coded for IN/SA/AE/QA until the regions table
-- (OPS-061) exists; add a code here and in PhoneSearchKey.java (golden file) for a new region.
CREATE OR REPLACE FUNCTION nsn_digits(p text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE WHEN p ~ '^\s*(\+|00)'
              THEN regexp_replace(regexp_replace(regexp_replace(p, '\D', '', 'g'), '^00', ''), '^(91|966|971|974)', '')
              ELSE regexp_replace(regexp_replace(p, '\D', '', 'g'), '^0', '') END
$$;

ALTER TABLE patients
  ADD COLUMN IF NOT EXISTS phone_digits text GENERATED ALWAYS AS (regexp_replace(phone, '\D', '', 'g')) STORED,
  ADD COLUMN IF NOT EXISTS phone_last10 text GENERATED ALWAYS AS (right(nsn_digits(phone), 10)) STORED,
  ADD COLUMN IF NOT EXISTS name_search  text GENERATED ALWAYS AS (normalise_name(name)) STORED;
