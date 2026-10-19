# CI pipeline (OPS-031)

Workflows: `.github/workflows/ci.yml` (stages 1, 2, 3, 6) and `ci-selftest.yml` (manual canary).
Required checks on `main`: `stage1-static`, `stage2-unit`, `stage3-integration`, `stage6-build-and-scan`
(budgets 3, 2, 6, 4 min via `timeout-minutes`). Path filters skip backend/frontend steps when untouched; skipped jobs count as passing.

- Stage 1: gitleaks (`.gitleaks.toml`: defaults plus Meta tokens), baseline check, Semgrep new-vs-base, `next typegen && tsc --noEmit`, ESLint, permission check, OpenAPI.
- Stage 2: `npm test` (unit tests plus `scripts/test-consult-flow.mjs`), JUnit classes that need no Docker.
- Stage 3: `*ApiTest` on Testcontainers >= 1.21 (pinned in `backend/pom.xml`, needed for Docker Engine 29), JaCoCo report.
- Stage 6: image built once from the tested jar (distroless, non-root), Trivy, Dependency-Check (CVSS >= 7), `npm audit` with baseline, CycloneDX SBOMs. On push to main: push to GHCR, keyless cosign signature (OIDC), SBOM attached, digest printed. Deploy by that digest only.
- Baseline: `.github/security-baseline.json` (id, scanner, owner, added, expires <= 90 days, reason), mirrored in `.trivyignore` and `ci/dependency-check-suppressions.xml`. Expired entries fail stage 1.
- Flaky policy: a test failing >= 2 of 20 reruns gets the `quarantine` label, an owner and a 5-day SLA (label created by the admin below).
- Self-test: run `ci-selftest` on a canary branch; it fails if a seeded secret or failing test is NOT caught.

## Needs external action
Create teams in `.github/CODEOWNERS`; run `scripts/ci/apply-branch-protection.sh OWNER/REPO` (required checks, CODEOWNER review, signed commits, no direct push/force push); add `NVD_API_KEY` secret; create label `quarantine`; first real run to measure stage timings and populate the baseline. Render deploys must switch to the signed digest, since the Dockerfile now packages a CI-built jar.

## Coverage and mutation gates (OPS-032)
- Config: `ci/quality-gates.json`. Scripts: `scripts/ci/quality.py`, tests `python3 scripts/ci/test_quality.py`.
- Every backend PR: stage 3 posts a JaCoCo comment (overall, new-code, gated classes: RxSafetyChecks, AllergyDrugClasses, PrescriptionService, CheckoutService, `auth.*` which is the IAM module).
- All gates start report-only (`enabled_from: null`). Turn one on by setting its date in the PR that proves the baseline meets it: new code >= 80% (from S3), overall >= 75%, per-class >= 90% line / 85% branch. Record the baseline with `quality.py baseline` into `ci/coverage-baseline.json` (not yet recorded: needs a green CI run).
- Nightly `pit-nightly` (`mvn -Ppit`, incremental history restored from the Actions cache) mutates the clinical-safety and checkout classes; fails below 85% overall or 90% on AllergyDrugClasses. There is no separate tax class: the tax rule lives in `CheckoutService.computeTotals`.
- A drop of more than 2 points vs the last green score sets repo variable `PIT_BLOCKED=true`; stage 3 then fails PRs touching clinical/billing/auth until a nightly run recovers it.
- Needs external action: secret `VARS_TOKEN` (can write repo variables), first CI run for the baseline, first nightly to confirm < 30 min and the real score.
