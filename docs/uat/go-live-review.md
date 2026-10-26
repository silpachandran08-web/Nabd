# Go-live review (end of week 2)

Attendees by role: delivery lead (chair), the clinic owner, on-site QA, engineering lead, clinical contact. One decision per clinic, recorded in `go-live.csv` (`clinic,decision,reasons,decided_on,conditions`). `decision` is `go`, `no-go` or `go-with-conditions`; `reasons` is never empty; `conditions` is required for `go-with-conditions` and lists dated actions.

## Evidence the chair brings
- `triage-log.csv`: no open S1; every S2 fixed or knowingly accepted with a reason; the SLA breach count.
- `stopwatch.csv` report: median per moment vs target.
- Day-close reconciliation of the fortnight (money matches the clinic's own books).
- Persona script results: each step pass/fail per persona.
- Open clinical-safety items (must be none for `go`).

## Default rule (the chair may be stricter, never looser)
`go` needs: zero open S1, zero unresolved clinical-safety items, all four stopwatch medians within target, reconciliation matched. Anything else is `go-with-conditions` or `no-go`, with reasons.

Check: `python3 scripts/ci/uat_pack.py golive docs/uat/go-live.csv --clinics "<comma list of the five clinics>"`.
