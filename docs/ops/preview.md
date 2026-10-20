# PR preview environments and stage 7 (OPS-036, FEF-049)

Every PR gets its own namespace `preview-pr-<n>` on the non-prod preview cluster, reachable at `https://pr-<n>.<PREVIEW_DOMAIN>`:
Core, the staff web app (the BFF-facing client), Postgres on `emptyDir` (no PVC, no backups), a comms stub, one Ingress (`/v1` to Core).
Then the FEF-049 smoke runs against it. The required check is `stage7-preview-e2e` (ci.yml).

| Piece | File |
|---|---|
| Stage 7 wiring | `.github/workflows/ci.yml` jobs `preview` and `stage7-preview-e2e` |
| Deploy + seed + PR comment | `.github/workflows/preview.yml`, `infra/preview/deploy.sh`, `preview.yaml`, `migrate.yaml`, `web.Dockerfile` |
| Smoke J1/J5/J6/J9 + axe, 4 shards | `.github/workflows/e2e-smoke.yml`, `frontend/apps/staff-web/tests/e2e/smoke/` |
| Reaper (72 h, PR closed/merged) | `.github/workflows/preview-reaper.yml`, `infra/preview/preview.py reap` |
| Synthetic data + real-PHI scanner | `tools/synthetic-data/synth.py`, loader `load.py` |
| Checks | `python3 infra/preview/test_preview.py`, `python3 tools/synthetic-data/test_synth.py`, `python3 scripts/ci/test_e2e_smoke.py`, `node --test frontend/apps/staff-web/tests/unit/s11-fef049-smoke.test.mjs` |

## What a preview holds
- 4 tenants: IN (region IN), AE, SA, QA (region KSA: the platform has only IN and KSA regions). One staff per role in
  `frontend/apps/staff-web/tests/e2e/smoke/roles.json` (taken from the RBAC Matrix), 2,000 patients with Indian or Arabic names, a day of
  appointments (tomorrow 09:00-16:45 on the 15-minute grid), a charge, and fresh visits for every smoke case and retry.
- Phones only in the reserved test range `+<cc>0000nnnnnn` (a national number never starts with 0). National ids are
  id-shaped with a deliberately broken checksum; Qatar gets `TEST-` tokens.
- The scanner (`synth.py scan`, and `load.py` before anything is written) rejects Verhoeff-valid Aadhaar, Luhn-valid
  Emirates ID and Saudi id/Iqama, anything QID-shaped, and phones outside the test range.
- Logins: per-role PINs are random per preview, posted in the PR comment, stored only in the namespace and a 3-day
  artifact, and die with the preview. CI reaches the cluster with a short-lived OIDC token, never a kubeconfig secret.
- Isolation: NetworkPolicy allows traffic inside the namespace and DNS only, so a preview cannot reach prod networks or a
  secret manager; the ServiceAccount token is not mounted; the WhatsApp client points at the stub with no token.
- Cost: namespace labels `nabd.io/preview`, `nabd.io/pr`, `nabd.io/cost-center=previews`, annotation `nabd.io/expires-at`;
  ResourceQuota per preview; at most `vars.PREVIEW_MAX` (default 10) previews at once (`preview.py cap`).

## Stage 7 smoke (FEF-049)
J1 walk-in to token < 45 s, J5 note sign and send to checkout < 2 s, J6 severe-allergy warning < 500 ms after the Rx line is
saved, J9 checkout bill and collect < 60 s. Each runs for IN and KSA, at 1280 px and 390 px (16 cases, 4 shards, 8 min job
budget). axe-core runs on every step; critical/serious violations fail the case with rule, nodes and help link. Any console
error or horizontal scroll at 390 px fails it too. One retry: fail-then-pass is reported "flaky"; 3 flaky runs in 5 days
quarantine the case (owner = the journey owner in `journeys.mjs`, from CODEOWNERS). A quarantined case still runs and is
reported, but does not block. History lives in the Actions cache (`smoke-flaky-*`).
Self-check (`e2e-smoke.yml` job `selfcheck`): J9 against a stack whose checkout write fails must fail, and pass when healthy.
Local run (mocked API, any built frontend): `NODE_PATH=<playwright> APP=http://localhost:<port> AXE_CORE_PATH=<axe.min.js> node frontend/apps/staff-web/tests/e2e/smoke/run-smoke.mjs --shard=1/4`.

## Needs external action
1. Decide DEC-21 (namespace vs app per preview); this design is namespace-per-PR and maps 1:1 to app-per-preview.
2. A non-prod cluster with ingress-nginx, wildcard DNS and TLS for `*.PREVIEW_DOMAIN`, a NetworkPolicy-enforcing CNI.
3. GitHub OIDC trust to that cluster; set `vars.PREVIEW_CLUSTER_LOGIN` (the login command), `vars.PREVIEW_DOMAIN`,
   optionally `vars.PREVIEW_MAX`, then `vars.PREVIEW_ENABLED=true`. Until then stage 7 passes with a "disabled" notice.
4. Apply branch protection (`scripts/ci/apply-branch-protection.sh`) so `stage7-preview-e2e` is required.
5. Cloud billing export filtered on `nabd.io/cost-center=previews` to confirm cost tags read zero after a reap.
6. QA to confirm the phone test range and id checks against QA §5.3 (not in this repo).
