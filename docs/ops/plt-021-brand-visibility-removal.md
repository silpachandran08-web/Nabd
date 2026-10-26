# PLT-021 runbook: removing brand-level data visibility

Status: M1-M4 shipped in Sprint 15 (V2521). M5 (column drop) is NOT done; it waits two releases.

## What changed
- V2521 rewrites every row-level-security policy that used `app_visible_tenant_ids()` to `tenant_id = app_tenant_id()`
  and redefines `enforce_tenant_rls` without a widening branch. It raises if any policy still references the visibility GUCs.
- `TenantContext` no longer reads `brands.data_visibility_scope` or the `nabd.legacy-brand-visibility` flag.
  `app.visible_tenant_ids` stays set, to the caller's own tenant only (old readers keep working).
- CI: `scripts/ci/brand_visibility_gate.py` blocks new migrations/Java that mention the GUCs.

## Milestones
| Step | What | State |
|---|---|---|
| M0 | Run `scripts/plt018-m0-brand-inventory.sql` on prod, contact each owner whose brand is BRAND/TENANT scope | Needs external action (a person must contact the owners; nothing was sent) |
| M1-M3 | Contain (PLT-018), remove policies, remove flag | Done |
| M4 | Deploy V2521 + this code; confirm with the checks below | On release |
| M5 | `db/deferred/M5__drop_brands_data_visibility_scope.sql` (drops the column), two releases after M4 | NOT MET this sprint |

## Checks after deploy
```sql
SELECT tablename, policyname FROM pg_policies
 WHERE coalesce(qual,'') || coalesce(with_check,'') ~ '(visible|accessible)_tenant_ids';  -- expect 0 rows
```
Sign in as a BRAND-scope owner's facility user: only that facility's patients are listed.

## Rollback
Behaviour change is intentional: owners who relied on cross-facility reads lose them. Rolling back means re-creating the
widened policies from V211 and restoring the old `TenantContext`; do not do this without a product decision.
Cross-facility reporting for owners must use the owner/brand APIs, not row-level widening.

## Tests
`CrossFacilityVisibilityTest`, `Plt018ContainmentS9QaTest` (updated), and the removal of `LegacyBrandVisibilityTest` and
`Plt018FlagOnS9QaTest`, which only tested the deleted flag.
