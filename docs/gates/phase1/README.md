# Phase 1 exit gate (OPS-050): cohort B entry and Phase 1 exit

Phase 1 ends when the pilot clinics (cohort A + cohort B, 10-15 clinics) show over two weeks of production use that booking,
waiting and checkout really got better. Every number below is reproducible from this folder: the queries, the runner, and
`scripts/ci/phase1_gate.py`.

## Before cohort B enters
`python3 scripts/ci/phase1_gate.py controls --junit backend/target/surefire-reports` must pass: Security Controls (docs/security/controls.yaml)
>= 90% verified, and every control still unverified has an owner and an ISO `due` date set by that owner.

## Exit metrics
| Metric | Target | Source | How |
|---|---|---|---|
| Desk booking time | median < 60 s, p90 <= 90 s | stopwatch `booking` | `docs/uat/stopwatch.csv` (OPS-049 stopwatch days) |
| WhatsApp booking time | median < 60 s | Edge `funnel_steps` (ACC-045) | `whatsapp_booking.sql` on the Edge DB |
| ETA accuracy | +/-10 min for >= 80% of visits | `eta_accuracy_daily` rollup | `eta_within10.sql` |
| Waiting time | median < 15 min | `queue_entry_events` | `wait_median.sql` |
| Walk-in registration | median < 45 s | stopwatch `walk_in` | stopwatch.csv |
| Repeat prescription | median < 30 s | stopwatch `repeat_rx` | stopwatch.csv |
| Dispense | median < 20 s | stopwatch `dispense` | stopwatch.csv (moment added in OPS-050) |
| Delay broadcast | median < 60 s from announcement to the first patient message queued | `doctor_delays`, `delay_broadcast_recipients` | `delay_broadcast.sql` |
| Receptionist SUS | mean >= 70 | questionnaire | `sus.csv` |
| Day close | counted == expected for 10 consecutive days, every clinic | `day_closes` | `day_close.sql` |
| Cohort B UAT | each clinic signs every persona (docs/uat/scripts) | sign-off sheet | `uat-signoff.csv` |

Event-timestamp sources are preferred to stopwatch timing wherever the data exists. The stopwatch rows stay for moments the
system cannot see (the receptionist's hands before the first click).

## Producing the report
1. `CORE_URL=... EDGE_URL=... docs/gates/phase1/run.sh 2027-01-04 2027-01-17 out/phase1 <tenant-uuid> ...` with read-only roles.
   It binds `app.tenant_id` per clinic (RLS is forced) and writes one CSV per query.
2. Copy into `out/phase1`: `stopwatch.csv` (docs/uat format), `sus.csv`, `uat-signoff.csv`.
3. `python3 scripts/ci/phase1_gate.py report --data out/phase1 --sha <commit> --clinics <cohort-B slugs> --junit backend/target/surefire-reports`
   writes `phase1-exit/phase1-exit-report.md` and `.json`: each metric with value, target, PASS/FAIL and its data source.
   With `SOURCE_DATE_EPOCH` set, the same commit and data folder give a byte-identical report.

## File formats
- `sus.csv`: `clinic,respondent,role,date,q1..q10` (answers 1..5, the standard 10-item SUS; respondent is a code, never a name).
  Score = 2.5 x (sum of (q-1) for odd items + (5-q) for even items). `phase1_gate.py sus sus.csv`.
- `uat-signoff.csv`: `clinic,persona,result,signed_by,signed_on,evidence` (result pass | pass-with-issues | fail; persona as in
  docs/uat/scripts). `phase1_gate.py uat uat-signoff.csv --clinics a,b`.
- Query outputs: `tenant_id,value,n` (`day_close.csv`: `tenant_id,close_date,expected,counted`).

## Decision record
`docs/gates/phase1.md` holds the review decision (same fields as Phase 0).
