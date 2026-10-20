# Clinical safety

## Clinical Safety Officer (CSO)
| Field | Value |
|---|---|
| Name | NOT YET NAMED (needs external action: hire or contract a clinician, Phase 0) |
| Contract start / end | NOT YET SET |
| Delegate | NOT YET NAMED |

Fill the table in the same PR that records the appointment. Until then CLN-002 criterion 1 is open and the Phase 0 exit gate (CLN-007) cannot pass. An interim contracted clinician with written scope is acceptable.

### Scope and authority (to be confirmed in the contract)
- Owns the hazard log (`hazard-log.csv`) and chairs the fortnightly review.
- Required reviewer on every `[CLIN-SAFETY]` change (see `.github/CODEOWNERS`).
- May block a release; may delegate review in writing to a named clinician.
- Signs off clinical content (dictionary, rules, dose windows). Engineers do not invent it.

## Hazard log
`hazard-log.csv`, one row per hazard. Columns: id, source, hazard, cause, existing_control, planned_control, verifying_test (`missing` if none), residual_risk (CSO rates), owner, status, closing_pr.
Rows marked `TO TRANSCRIBE` come from QA 4.6 / QA-14 / QA-17 / QA-18, whose text is not in this repo: copy it in, do not guess. Rating and owner need the CSO.
When a `[CLIN-SAFETY]` task is closed, set `closing_pr` on its row in that PR.
`python3 scripts/check-clinical-safety.py` lints the log and CODEOWNERS (also run in CI).

## Fortnightly review
- Chair: CSO. Fortnightly (doc06 8.3). Attendees: CSO, engineering lead, one engineer per open item.
- Agenda: `reviews/TEMPLATE.md`. Minutes go in `reviews/YYYY-MM-DD.md`, committed within 2 working days, including the hazard-log diff.
- Calendar invite: needs external action (set by the CSO or engineering lead once the CSO is named).

## Safety paths
`.github/CODEOWNERS` covers RxSafetyChecks, AllergyDrugClasses, `backend/src/main/resources/clinical-safety/` (dictionary data), `backend/**/cds/` (CDS rules) and this folder. Add paths there when the dictionary/rules land (CLN-004, CLN-005). The reviewer is the placeholder team `@nabd/clinical-safety`; add the CSO as its member.

## Verifying tests and the safety test report (CLN-007)
- **JUnit**: tag a verifying test class or method with `@Hazard("H-04")` (`com.nabd.hms.support.Hazard`; several ids: `@Hazard({"H-04", "H-09"})`). A class tag covers every test in the class.
- **Playwright** (`@playwright/test` with the JSON reporter, output at `frontend/playwright-report.json`): `test('...', { annotation: { type: 'hazard', description: 'H-04' } }, ...)`. The plain-Node e2e scripts in `frontend/tests/e2e/` produce no machine-readable results and are not counted.
- `python3 scripts/ci/safety_report.py report --sha <sha> --release <tag> --junit backend/target/surefire-reports [--playwright <json>] [--dictionary IN=<v>] --require-signed` writes `safety-test-report.html` and `.json` (commit SHA, release, dictionary versions, hazard-log hash and sign-off, per-hazard tests and results). It exits 1 and lists the ids when any hazard in `hazard-log.csv` has zero passing tagged tests, when a tag names an unknown hazard, or (with `--require-signed`) when hazard log v1 is not signed.
- `.github/workflows/release.yml` runs it on every `v*` tag, attaches both files to the GitHub release, and holds the `release-gate` job for a manual CSO approval (environment `clinical-safety-release`) when any CODEOWNERS `@nabd/clinical-safety` path changed since the previous tag. Production deploy jobs must `needs: release-gate`.
- Tests: `python3 scripts/ci/test_safety_report.py` (gate self-test, report snapshot, workflow shape).

## Hazard log v1 sign-off
`signoff/hazard-log-v1.json` holds the CSO's approval record: name, ISO date, link to the signed record, and the sha256 of `hazard-log.csv` that was reviewed. Only the CSO fills it (CODEOWNERS). Any later edit to the log changes the hash and the release gate fails until the CSO re-signs. Status: **unsigned, needs external action** (CSO not yet named; most rows still `TO TRANSCRIBE`).
