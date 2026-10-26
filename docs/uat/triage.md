# Daily triage log and hotfix process

Log: `triage-log.csv` (one row per defect; the header is fixed, `python3 scripts/ci/uat_pack.py triage docs/uat/triage-log.csv --today YYYY-MM-DD` reports SLA breaches).

| Severity | Meaning | SLA to fix and deploy |
|---|---|---|
| S1 | Cannot run the day, wrong clinical or money result, data loss, security or privacy issue | **under 24 hours** (hotfix path below) |
| S2 | A persona step fails or misses its timing target, workaround exists | **under 5 days** |
| S3 | Cosmetic, wording, minor inconvenience | next planned release |

Columns: `id, clinic, date_found, persona, screen, severity, description, owner, sla_due, status, fixed_on, notes`. `description` holds ids and screen names only, never patient data. `sla_due` is `date_found` + 1 day for S1, + 5 days for S2, empty for S3. `status` is one of `open, in_progress, fixed, wont_fix`. `fixed` and `wont_fix` need `fixed_on`.

## Daily routine (at close, 20 minutes)
1. On-site QA enters every defect found today (severity proposed).
2. Triage chair confirms severity, assigns an owner, sets `sla_due`.
3. Read the checker output: any `open` or `in_progress` row past `sla_due` is a breach and is the first item next morning.
4. Anything clinical goes to the clinical contact before the meeting ends.

## Hotfix process (S1)
1. Owner acknowledges within 1 hour and replies in the log (`in_progress`).
2. Fix on a branch from the released tag, minimal change, one test that fails before and passes after.
3. Normal CI gates run (no bypass); deploy through the standard release path to the pilot region only after review by a second engineer.
4. On-site QA re-runs the failing script step on the clinic's laptop and records `fixed_on`.
5. If the 24 h SLA cannot be met, the escalation role is told at hour 12 and the clinic is told which manual fallback to use (their current process).
