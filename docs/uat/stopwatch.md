# Stopwatch day: the moments that matter

On day 5 of shadow mode, on-site QA times real staff doing real tasks, at least 10 samples per moment per clinic (fewer only if the clinic has fewer, then say so in `notes`).

| Moment | Starts when | Stops when | Target (median) |
|---|---|---|---|
| `walk_in` | Receptionist presses Walk-in | Token slip is printed | 45 s |
| `booking` | Receptionist opens a new appointment | Appointment is confirmed on screen | 60 s |
| `repeat_rx` | Doctor opens the repeat-prescription action for a returning patient | Prescription is signed | 30 s |
| `checkout` | Cashier presses Complete checkout | Receipt is printed | 60 s |
| `dispense` (cohort B on, OPS-050) | Pharmacist opens a signed prescription in the dispense worklist | Dispense is saved | 20 s |

Record every sample in `stopwatch.csv`: `clinic,moment,sample,seconds,date,timer,notes`. Report: `python3 scripts/ci/uat_pack.py stopwatch docs/uat/stopwatch.csv` prints the median per clinic per moment against the target and exits 1 if any median misses or any moment has no samples.

Rules: time from the user's first action, not from "ready"; do not coach during a sample; a sample interrupted by a patient is discarded and noted; round to whole seconds.
