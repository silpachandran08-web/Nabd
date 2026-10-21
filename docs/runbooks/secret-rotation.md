# Secrets: layout, access, rotation (OPS-058)

No secret value lives in code, env files, logs, docs or Terraform state. Terraform defines paths, roles and keys only
(`infra/terraform/modules/cell/aws/secrets_*.tf`); values are created by cell bootstrap and tenant onboarding.

## Layout (per cell, `<cell>` = module `var.name`, e.g. `nabd-in-prod`)
| Class | Path | Examples | Key | Runtime role | Operator role (write and rotate, cannot read values) |
|---|---|---|---|---|---|
| Installation | `cell/<cell>/platform/<name>` | DB credentials, JWT keys, Meta app secret | `secrets-platform` | `<cell>-secrets-platform-reader` | `<cell>-secrets-platform-operator` |
| Tenant integration | `cell/<cell>/tenants/<tenantId>/<name>` | WhatsApp token, payment gateway key, insurer key | `secrets-tenants` | `<cell>-secrets-tenant-reader` | `<cell>-secrets-tenant-operator` |

* The tenant reader is assumed by the API with the session tag `tenant_id=<id>`; its policy resource is
  `.../tenants/${aws:PrincipalTag/tenant_id}/*`, so it reads that tenant's path only. No tag, no access (explicit deny).
* Each runtime role is explicitly denied the other class's path. Operator roles need MFA.
* Application code reads secrets only through `com.nabd.hms.shared.secrets.SecretResolver`: the tenant is the one bound to
  the transaction (never a parameter), names are single flat segments, values are cached 5 minutes
  (`nabd.secrets.cache-ttl`), and every call emits an audit line (principal, path, time, found, cached; never the value) on
  logger `nabd.secret.audit`. Configure `nabd.secrets.cell` (the `<cell>` above).
* Secret-manager side audit: EventBridge rule on `GetSecretValue` for this cell's paths into the encrypted log group
  `/aws/events/nabd/<cell>/secret-access` (same region as the cell, retention `secret_audit_retention_days`).

## KMS hierarchy (per cell, rotation on, multi-region with DR replicas)
`field-encryption` (wraps per-tenant DEKs, T-130), `backups`, `object-storage`, `cache-projection`, plus
`secrets-platform`, `secrets-tenants`, `secret-audit-logs`. Use is allowed only to principals tagged
`cell_name=<cell>` (explicit deny for others); the account root administers but cannot use. Every role of a cell must
carry the tag `cell_name`. The older `phi`, `audit`, `ops` keys are in `kms.tf`.

## Rotation
The application re-reads a secret after the cache TTL, so a rotated value is picked up without a restart. After an
authentication failure against a provider call `SecretResolver.evictTenant(name)` and retry once.
Two-value overlap is required: the provider must accept old and new for at least one TTL (5 min) plus clock slack.

Cadence (proposed, owner: SRE; change here if the security review decides otherwise): DB password and JWT keys 90 days,
Meta app secret on Meta's schedule or on suspicion of leak, tenant integration keys on the tenant's request or on leak.
The RDS-managed master secret rotates by RDS itself.

Procedure (DB password):
1. Operator role: create the new credential in the DB (`ALTER ROLE ... PASSWORD` through the migrator role; keep the old
   password valid), put the new value in `cell/<cell>/platform/db-password` (`PutSecretValue`).
2. Wait one TTL plus 1 minute; check the readiness probe and the `nabd.secret.audit` lines show reads succeeding.
3. Revoke the old password. Check error rate and p95 on the API dashboard stay flat.

Procedure (Meta app secret): add the new secret in Meta's app dashboard (Meta allows the old to be valid until you
reset it), `PutSecretValue` on `cell/<cell>/platform/meta-app-secret`, wait one TTL, verify a signed webhook delivery
is accepted (X-Hub-Signature-256), then reset the old secret in Meta.

Drill: run both procedures in staging-in with a synthetic request load and record: downtime (target none), failed
requests, time from `PutSecretValue` to first read of the new value. This drill has NOT been run (needs a staging
cluster and a secret manager).

## Leak response
Identify the class from the path. A leaked platform secret does not expose tenant integrations (different key, role and
path); rotate it, then review the `secret-access` log for reads of the old path.
