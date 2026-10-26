# UAT script: Doctor

Persona: `doctor`. My Day, consultation, notes, prescriptions, documents.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| DOC-01 |  | Sign in with the doctor account | My Day lists today's queue with the doctor's own patients only | record |
| DOC-02 |  | Open the next patient from the queue | Consultation opens with history, allergies and last visit visible | record |
| DOC-03 |  | Write a note (subjective, objective, assessment, plan) | Autosaves; reopening shows the same text | record |
| DOC-04 |  | Add a prescription line for a medicine and a known allergen of the test patient | An allergy warning shows before signing; signing needs an explicit override reason | warning < 500 ms |
| DOC-05 |  | Repeat the last prescription for a returning patient and sign it | Lines copied exactly as signed before; signature recorded with registration number | median ≤ 30 s |
| DOC-06 |  | Print the prescription (A5 and A4) | All lines, sig and registration number fit; page x of y if it overflows; black on white | record |
| DOC-07 |  | Issue a medical certificate document and print it | Document prints with clinic and doctor header | record |
| DOC-08 |  | Close the visit and send to checkout | Queue entry moves to checkout; completion only happens at checkout | record |
