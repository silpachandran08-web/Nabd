# Release change control (OPS-044)

A release PR (title starts with "Release") uses `.github/PULL_REQUEST_TEMPLATE/release.md`. The `release-checklist` workflow runs `scripts/ci/release_checklist.py` and fails the PR unless all four items are ticked and valid:

1. Change approval: approver name and a link.
2. Deploy window: start and end in UTC (`2026-01-31T22:00Z`), 15 minutes to 4 hours, clear of freezes and OPD peaks in `docs/ops/release-windows.yaml`.
3. Rollback plan: a real plan, not a single word.
4. Safety report link: the release's safety report.

`freeze` and `opd_peaks` in `release-windows.yaml` are empty: Needs ops confirmation (the checker warns while peaks are not configured).

Before the tag: the latest `journeys-nightly` run on main must be green (J1-J15 with SLA timings plus the role-absence sweep), or the release workflow stops at `journey-gate`. Missing hazard tests are handled by the CSO exception route in `docs/clinical-safety/README.md`.
