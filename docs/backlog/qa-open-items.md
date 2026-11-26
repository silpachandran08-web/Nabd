# QA open items (carried as backlog after merge of qa/integration-3, tip 7f49c9e)

Source reports: Nabd/ui-gap-report/qa-pass/ (verification-issues.md, VERIFY_V3/V4/V5.md, FIX_E6.md, QA_REPORT_e1-e3.md).
All Blocker/Major items found in the QA pass are fixed and re-verified. What remains is below.

## Release gates (do before deploy)
- **No backend test has ever been run.** Backend was compile-checked only (no Maven/Docker in sandbox). Run `mvn verify` incl. Testcontainers suites, esp. AllergyDrugClassesTest and the Rx/pharmacy/patient-PATCH integration tests.
- **V50/V51 never applied to any database.** V51 was edited in place before first apply; apply to a fresh DB and a copy of staging, check triggers (signed-Rx INSERT/UPDATE/DELETE/TRUNCATE) and the pharmacy stock log (identity `seq`). Next free migration: V52 (V49 gap is deliberate).
- **Clinical sign-off needed** (clinician, not engineering): allergy class matching lists, Rx warning override reasons, vitals bounds (app/lib/vitals.ts), allergy "none documented / not asked" semantics.

## Backend
- Allergy matcher: residual false positives/negatives in whole-word + stem matching (AllergyDrugClasses); extend the CSV test cases as pharmacists report pairs.
- Checkout: non-stock lines are client-priced; add server-side pricing/validation.
- Missing tests: pharmacy stock adjust/activate/item-inactive 409, prescriber check, sign gate locking.
- Docs: migration numbering note (V49 gap, next V52) in project docs.

## Frontend
- Dialog/keyboard polish: focus-trap edge cases, Escape handling in nested dialogs, focus restore on deferred tick.
- Remaining mobile (390px) checks on pages not covered by V4/V5.
- Real-backend QA per role: everything was verified against a mock API only (CANNOT-VERIFY items in QA reports).

## Tracking
- Backlog CSV/xlsx: 71 In review, 31 In progress, 199 Open, 2 Closed-refuted (unchanged by this merge).
