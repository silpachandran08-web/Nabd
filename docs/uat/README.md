# OPS-049: Pilot cohort A, two weeks in shadow mode (UAT pack)

Owner: Delivery lead (pilot). Source: QA section 4.10, RM section 8 (pilot design), doc06 section 6.5.

**Status of this pack: the documents, scripts and checklists exist; the two-week run itself is Needs external action** (real pilot clinics, on-site QA staff, a real calendar). Nothing in this folder records a real clinic result: the CSV files are empty templates, and the checker has synthetic samples only in its test.

## Cohort A
Five clinics: one India GP, one dental, one solo specialty, plus two more chosen by the delivery lead. A UAE clinic joins only once QA-21 is fixed. Shadow mode: the clinic keeps its current process and enters the same day into Nabd in parallel; Nabd staff are on site.

## What is in this folder
| File | Use |
|---|---|
| `scripts/<persona>.md` | UAT script per pilot persona: reception, doctor, nurse, billing, solo, owner. Steps, expected results, timing targets |
| `shadow-mode.md` | Two-week calendar, support rota template, on-site QA checklist, rules |
| `triage.md` + `triage-log.csv` | Daily triage log, severities, SLAs, hotfix process |
| `stopwatch.md` + `stopwatch.csv` | Stopwatch day kit: how to measure, where to record, targets |
| `go-live-review.md` + `go-live.csv` | End-of-week-2 review: go / no-go per clinic with reasons |

Tooling: `python3 scripts/ci/uat_pack.py check|stopwatch|triage|golive` (see each file). Self-test: `python3 scripts/ci/test_ops049_uat.py`.

## Rules
- No real PHI outside prod-in. On-site laptops use the production app only (no local builds, no copies of data, no screenshots of patients in chat).
- Use only synthetic or consented pilot-clinic data entered through the app. Defect reports carry ids and screen names, never patient names or phone numbers.
- Journeys: the scripts derive from the product journeys J1-J15 (the journey list is kept with the QA plan; this pack names the screens, not the J numbers it cannot see here). Add the J number in the `Journey` column when the QA plan is attached.
- Targets in the scripts are the contract. A step that misses its target is logged as a defect (severity per `triage.md`), not silently accepted.
