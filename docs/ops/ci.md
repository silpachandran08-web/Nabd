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
