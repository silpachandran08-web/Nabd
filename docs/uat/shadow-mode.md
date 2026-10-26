# Shadow mode: two weeks, five clinics

## Calendar (per clinic; day 1 is a clinic working day)
| Day | What happens |
|---|---|
| 0 (before) | Kick-off: accounts, roles, printers (80 mm thermal, A4/A5) checked; dry run of every persona script on a test tenant; no real data until the clinic signs the data-processing agreement in the app |
| 1 | Training half-day; on-site QA present all day; reception and billing start the parallel entry |
| 2-4 | Full parallel entry; scripts run once per persona; daily triage at close |
| 5 | Stopwatch day (see `stopwatch.md`) |
| 6-9 | Parallel entry continues; exploratory session of 90 minutes per role (charter: try to break your own day) |
| 10 | Day-close reconciliation: Nabd totals vs the clinic's own books for the whole fortnight |
| 10 (end) | Go-live review (see `go-live-review.md`) |

## Support rota (template; fill in names outside this repo)
| Slot | Role (not a name) | Duty |
|---|---|---|
| On-site QA | QA engineer | At the clinic during opening hours on days 1, 2, 5, 10; remote on the others |
| Hotfix owner | Backend or frontend engineer on call | Takes S1 within 1 hour, fixes and ships inside 24 h |
| Triage chair | Delivery lead | Runs the daily triage, owns the log |
| Clinical contact | Clinical safety officer | Reviews anything touching prescribing, dosing, allergy or triage (needs a named human) |
| Escalation | Head of engineering | Any S1 not fixed by hour 12 |

## On-site QA checklist (daily)
- [ ] Open of day: every persona can sign in; printers print a test token slip
- [ ] Parallel entry is keeping up (counts match the clinic's paper/system for the day so far)
- [ ] Every new defect has a log row with severity, owner and SLA before leaving
- [ ] Every S1/S2 reproduced with ids only, no patient data in the report
- [ ] Close of day: triage meeting held, log committed, next-day plan agreed

## Rules
- The clinic's current process stays the source of truth during shadow mode; Nabd is never the only record.
- A clinical safety defect (wrong drug/dose/allergy shown, an allergy not warned) is S1 whatever else is true, and goes to the clinical contact the same day.
