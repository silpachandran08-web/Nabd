# Gates (OPS-048)

## Phase 0 exit gate
| # | Criterion | Evidence the pack reads |
|---|---|---|
| 1 | Zero open S1 and zero open security S2 defects | `defects` in the evidence file (tracker query link) |
| 2 | Two replicas pass the suite and the prod-config job | `prod_config`, `two_replicas` (CI run links, OPS-003 / OPS-053) |
| 3 | Registry p95 < 300 ms at 500k patients | `k6.registry_p95_ms` + report link (OPS-046) |
| 4 | Check-in p95 < 300 ms | `k6.checkin_p95_ms` + report link (OPS-046) |
| 5 | Security Controls >= 60% verified | `docs/security/controls.yaml` + JUnit results |
| 6 | Hazard log v1 signed by the CSO | `docs/clinical-safety/signoff/hazard-log-v1.json` (CLN-007) |
| 7 | Clinical reads of a withdrawn-consent patient return 404 | JUnit `ClinicalReadScopeTest` (IDC-001) |
| 8 | Every clinical GET writes a read-log row | JUnit `PhiReadAuditTest` (PLT-039) |

Evidence file (commit it as `docs/gates/evidence/phase0-<rc>.json` so the pack is reproducible from a commit SHA):
```json
{ "defects": { "open_s1": 0, "open_security_s2": 0, "link": "<tracker query URL>" },
  "prod_config": { "conclusion": "success", "link": "<run URL>" },
  "two_replicas": { "conclusion": "success", "link": "<run URL>" },
  "k6": { "registry_p95_ms": 0, "checkin_p95_ms": 0, "link": "<report URL>" } }
```
A missing entry makes its criterion FAIL ("no evidence supplied").

## Security Controls (`docs/security/controls.yaml`)
67 rows from the task plan's Security Controls sheet (SC-nn = row order). Each row needs a JUnit test class (or `Class#method`) or an evidence link, and an owner. A control is verified when every listed test passed in the latest results and every evidence link has `accepted_by` and `accepted_on` filled by its owner. Owners are placeholder teams (Needs external action: name people).

## Commands
- `python3 scripts/ci/phase0_gate.py controls --junit backend/target/surefire-reports`: completeness check + verified %.
- `python3 scripts/ci/phase0_gate.py pack --sha <sha> --junit backend/target/surefire-reports --evidence <file>`: writes `phase0-gate-pack.md` and `.json` (one section per criterion; Security Controls lists unverified rows with owners when red). Same commit + same evidence file + `SOURCE_DATE_EPOCH` = identical pack.
- `python3 scripts/ci/phase0_gate.py record`: validates `phase0.md`.
- Workflow `.github/workflows/phase0-gate.yml` runs the first and last on every PR and the pack on demand for a release candidate.

## Real-data block
`app.provisioning.real-data-onboarding` (`blocked` | `open`, env `REAL_DATA_ONBOARDING`). Unset means blocked under the `prod` profile. While blocked, `POST /v1/platform/provisioning-jobs` and `/advance` return 409 `real-data-onboarding-blocked`. Open it on prod-in only after `phase0.md` records `passed`.
