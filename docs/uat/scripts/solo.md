# UAT script: Solo doctor

Persona: `solo`. One person runs the whole clinic from one screen.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| SOL-01 |  | Sign in and open Today | Queue, schedule and billing shortcuts are all reachable for this one user | record |
| SOL-02 |  | Walk-in check-in and token slip | Same result as the reception script | median ≤ 45 s |
| SOL-03 |  | Consult, repeat prescription, sign | Same result as the doctor script | median ≤ 30 s |
| SOL-04 |  | Checkout and print the receipt | Same result as the billing script | median ≤ 60 s |
| SOL-05 |  | Book a follow-up | Appointment confirmed | median ≤ 60 s |
| SOL-06 |  | Close the day | Day close totals match the cash taken | record |
