# prod-in cutover and Render/Vercel exit (OPS-056)

Status: runbook only. Every step below is **Needs external action** (cloud account, DNS, Render and Vercel consoles); nothing was run from the repo sandbox.
Prerequisites: OPS-055 (fresh prod-in database, PgBouncer), OPS-058 (cell secret manager), DEC-04 / DEC-21 signed, `infra/prod-in` secrets synced.

## 1. Before the window
1. Terraform apply of the cell with `edge_api_host`, `edge_app_host`, `edge_public_zone_id` set (edge_*.tf). Confirm the ACM certificate is `ISSUED`.
2. Release: run workflow `prod-in` with the core and web image digests that passed staging-in (it verifies the cosign signature and that staging-in runs the same digests).
3. `kubectl -n prod-in get deploy`: core 2/2, worker 2/2 (one pod logs relay leader), bff 2/2.
4. `python3 infra/security/tls_check.py <api host> <app host> --http-port 80` passes (also scheduled in `.github/workflows/prod-in.yml`).
5. Lower the DNS TTL of the old records to 60 s a day before.

## 2. Cutover
1. Put the clinic(s) in a no-write window (no PHI is migrated from Render: DEC-23 fresh database).
2. Point the app and API hostnames at the Mumbai ALB (the `aws_route53_record.edge` aliases). Vercel keeps serving only static/public assets, with no API proxy rule.
3. Pilot users sign in again on the new hosts.

## 3. Verify (OPS-056.5)
- `python3 infra/security/check_cutover.py --resolve <api host> <app host>` (both resolve to `*.ap-south-1.elb.amazonaws.com`).
- `python3 infra/security/check_cutover.py --csp "<served CSP>" --api-origin https://<api host>` (connect-src has no Render/Vercel origin).
- Export 24 h of egress and ALB access logs, then `python3 infra/security/check_cutover.py --egress-log <file>` (no Render/Vercel hostname). The egress test of OPS-057 is the standing check.
- `NEXT_PUBLIC_API_BASE_URL` of the staff app image points to the Mumbai API host; `APP_CORS_ALLOWED_ORIGINS` is the app host only (the application.yml default lists a Vercel origin for dev; production overrides it in `infra/prod-in/core.yaml`).

## 4. Exit
1. Stop the Render services, take no backup copy out of India, delete the Render Postgres and services.
2. Fill `docs/runbooks/render-destruction-certificate.md`, store it with the audit evidence, then `python3 infra/security/check_cutover.py --certificate <file>`.
3. Remove Render/Vercel entries from CI secrets and from `docs/ops/initial-setup.md` once the certificate is accepted.

## Rollback
Before step 4 only: point DNS back at the old hosts. After the Render database is destroyed there is no rollback; that is why step 4 waits for sign-off of section 3.
