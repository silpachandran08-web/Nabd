# UAT script: Reception

Persona: `reception`. Opening, walk-ins, booking, queue, taking payment.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| REC-01 |  | Sign in with the reception account on the clinic laptop | Arrivals (Today) opens; only reception menu items are visible | record |
| REC-02 |  | Walk-in: search an existing patient, pick the doctor, press Check in, print the token slip | Token slip prints on the 80 mm printer, readable, no clipped text, at most 12 cm | median ≤ 45 s |
| REC-03 |  | Walk-in with a new patient (name, phone, date of birth) | Patient is registered and checked in; a possible duplicate shows the review list instead of creating a second record | record |
| REC-04 |  | Book an appointment for tomorrow in a free slot | Appointment confirmed on screen on the 15-minute grid; an overlapping slot is refused with a clear message | median ≤ 60 s |
| REC-05 |  | Move and cancel an appointment | Queue and schedule update; the cancellation reason is asked where required | record |
| REC-06 |  | Mark a patient arrived, then move the queue entry to the next step | Status changes once; a stale screen shows a conflict message, not a silent overwrite | record |
| REC-07 |  | Collect a payment on an open invoice (cash, then UPI/card split) | Balance falls by the exact amount; receipt prints on 80 mm within 2 s | record |
| REC-08 |  | Open the dues list and find a patient with a balance | Patient found; no restricted clinical data is shown to this role | record |
| REC-09 |  | Sign out | Session ends; back button does not show patient data | record |
