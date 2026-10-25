# Audit chain verify and monthly seals (OPS-067)

- Nightly `audit-chain-verify` (01:30 Asia/Kolkata, `app.audit.verify-cron`) runs `verify_audit_chain` for every tenant. A broken chain increments `audit_chain_verify_failures_total` and logs `SECURITY audit chain broken: tenant <id> first bad row <id>`; `NabdAuditChainBroken` (infra/observability/audit-chain.rules.yml) pages as a security incident.
- Monthly `audit-chain-seal` (1st, 03:00) writes one `audit_chain_seals` row per tenant: last chained row of the previous UTC month and an HMAC-SHA256 signature with `app.audit.seal-key` (secret manager, never in the repo; the job refuses to run without it).
- Needs external action: wiring the rules into Prometheus/Alertmanager, provisioning `app.audit.seal-key`, and copying the seals to the regional archive bucket (object lock) with an asymmetric/KMS signature if the auditors require non-repudiation. The in-DB HMAC alone does not stop someone holding the key.
- Not built: the async sealer (OPS-067.2). Build it only if the OPS-047 contention run on staging shows chain-head wait p99 above 50 ms; the head is already one per-tenant row (V1380) so the lock is per tenant.
