<!-- OPS-044 release change control. Use with ?template=release.md. Title must start with "Release". Every line below must be ticked and filled
     (python3 scripts/ci/release_checklist.py checks this in CI). Process: docs/ops/release-change-control.md -->

## Release checklist

- [ ] **Change approval** — approver: NAME; link: https://example.invalid/approval
- [ ] **Deploy window** — start: 2026-01-01T22:00Z; end: 2026-01-01T23:00Z (UTC, outside the OPD peaks and freezes in docs/ops/release-windows.yaml)
- [ ] **Rollback plan** — how to go back, in one or two sentences (previous tag, migrations that cannot be reversed and what is done about them)
- [ ] **Safety report** — link: https://example.invalid/releases/tag/vX.Y.Z (safety-test-report.html/.json/.pdf attached to the GitHub release; no unverified hazard, or a CSO-signed exception)

## What ships
<!-- tag, notable changes, migrations -->
