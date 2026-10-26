-- PLT-021 M5 (NOT a Flyway migration: this folder is not on the migration path).
-- Ship as the next db/migrations/V<n>__drop_brands_data_visibility_scope.sql no earlier than TWO RELEASES AFTER V2521 (M4) is in production,
-- after docs/ops/plt-021-brand-visibility-removal.md section M5 is ticked. Expand/contract: nothing reads the column since M4
-- (no policy and no Java reads it); the lint needs CONTRACT_REF=<the M4 release> for DROP COLUMN (MIG-007).
-- Also in the same PR: remove brands.data_visibility_scope from db/classification.yaml, the m0 inventory script
-- (scripts/plt018-m0-brand-inventory.sql) and the tests that set the column (CrossFacilityVisibilityTest, Plt018ContainmentS9QaTest).
SET lock_timeout = '5s';
ALTER TABLE brands DROP COLUMN IF EXISTS data_visibility_scope;
