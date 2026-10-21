# prod-in runtime and edge (OPS-056)

Everything here was built and checked statically; nothing was applied (no cloud account, cluster or DNS in the sandbox). Platform: AWS Mumbai with Kubernetes manifests, matching staging-in; DEC-21 may still change the runtime, the checks and the edge stay the same.

| Piece | Files | Check |
|---|---|---|
| Core 2, worker 2 (one relay leader via advisory lock), BFF 2, zone-spread, Mumbai-pinned | `infra/prod-in/{core,worker,bff,targets}.yaml` | `python3 infra/prod-in/check_prod_in.py [--selftest]` |
| ALB (TLS 1.2/1.3, `ELBSecurityPolicy-TLS13-1-2-2021-06`), HSTS preload, HTTP redirect, ACM DNS-validated cert (auto-renew), WAF managed rules + per-IP rate limit | `infra/terraform/modules/cell/aws/edge_*.tf`, `variables_ops056.tf` | `python3 infra/security/check_edge.py [--selftest]`, `infra/terraform/check-terraform.py` |
| TLS check of the live endpoints | `infra/security/tls_check.py`, `.github/workflows/prod-in.yml` job `tls` | `python3 scripts/ci/test_ops056.py` (real local TLS servers) |
| Digest promotion, cosign verify, staging parity, in-cluster signature policy | `infra/prod-in/promote.py`, `verify-images.yaml`, workflow job `promote` | `test_ops056.py` (Promote) |
| Cutover, post-cutover checks, destruction certificate | `docs/runbooks/prod-in-cutover.md`, `render-destruction-certificate.md`, `infra/security/check_cutover.py` | `test_ops056.py` (Cutover) |

Edge is off until `edge_api_host`, `edge_app_host`, `edge_public_zone_id` are set (module `main.tf` is shared and not edited here).
A CDN is not part of the PHI path: a CloudFront certificate must live in us-east-1, outside India; static assets stay on Vercel/any CDN with no PHI.

## Needs external action
Apply the cell and edge; create the prod-in cluster, namespace, AWS Load Balancer Controller, Kyverno; sync Secrets from the cell secret manager (OPS-058 names); fill `REPLACE_*` (org, hosts, target group ARNs); repo variable `PROD_IN_HOSTS`, secrets `STAGING_IN_KUBECONFIG`/`PROD_IN_KUBECONFIG`, environment `prod-in` with reviewers; DNS move; Render deletion and certificate; OPS-057 egress test.
