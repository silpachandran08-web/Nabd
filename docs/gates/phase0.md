# Phase 0 exit gate record

No real patient data enters any environment until this record says `passed` (RM §5, doc06 §6.4).
Fill the fields below after the 1-hour gate meeting (founder, CTO, CSO, DPO) and commit in the same PR as the
pack link. `python3 scripts/ci/phase0_gate.py record` checks this file in CI.

- Decision: not yet held
- Date:
- Attendee Founder:
- Attendee CTO:
- Attendee CSO:
- Attendee DPO:
- Pack commit:
- Pack link:
- Notes:

After a `passed` decision, and only then, set `app.provisioning.real-data-onboarding=open` (env `REAL_DATA_ONBOARDING=open`) on prod-in.
