# UAT script: Nurse

Persona: `nurse`. Vitals, triage notes, hand-offs.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| NUR-01 |  | Sign in with the nurse account | Nursing screen opens; no billing or setup menus | record |
| NUR-02 |  | Take a patient from the waiting list for vitals | Entry moves to vitals pending; the patient's allergies are visible | record |
| NUR-03 |  | Record vitals including an out-of-range value | Value saved; the out-of-range flag shows; nothing is changed silently | record |
| NUR-04 |  | Add a nursing note and mark vitals done | Entry moves to vitals done and appears for the doctor | record |
| NUR-05 |  | Try to open a prescription for editing | Not offered (control absent), no error page | record |
| NUR-06 |  | Sign out and sign in again | Lock and session behave as set by the clinic idle policy | record |
