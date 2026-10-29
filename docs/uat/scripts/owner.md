# UAT script: Owner

Persona: `owner`. Overview, staff and access, money, audit.
Pass/fail per step in the run sheet; a failed step or a missed target is a triage row (`../triage.md`). Targets marked `record` have no agreed number yet: record the time, do not fail the step.

| Step | Journey | Action | Expected result | Target |
|---|---|---|---|---|
| OWN-01 |  | Sign in with the owner account | Overview shows today's numbers; clinical detail is not shown without the grant | record |
| OWN-02 |  | Add a staff member with a role and see their menu | Invitation created; the new role sees only what it should | record |
| OWN-03 |  | Change the default paper for the prescription to A5 | The doctor's print button uses A5 without asking | record |
| OWN-04 |  | Open the revenue and dues reports for the week | Totals equal the day-close figures for the same days | record |
| OWN-05 |  | Open the audit trail and find a change made today | Entry shows who, what, when; no patient data in the text | record |
| OWN-06 |  | Review the plan and module settings | Switched-off modules are absent from the menus | record |
