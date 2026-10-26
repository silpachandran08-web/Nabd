# Per-tenant data keys (PLT-045)

Every clinic gets its own data-encryption key (DEK). The DEK is stored only wrapped by the region's KMS key (`tenant_data_keys`),
unwrapped DEKs live in the API process memory for at most 15 minutes (never in Valkey, never logged), and `FieldCipher` writes
format v2 (`[0x02][DEK version][IV][ciphertext+tag]`, same AAD as v1) for a tenant that has a DEK. Tenants without one (created before
PLT-045, or `app.kms.provider` unset) stay on the global keyring until `CryptoReencryptJob` moves their values.

| Topic | How |
|---|---|
| Switch on | `app.kms.provider=local` (dev/test double, needs `app.kms.local-master-key`, 32+ bytes base64). The cloud adapter for the India region is **Needs external action** (provider decision doc06 D2): implement `KmsClient` and give it another `app.kms.provider` value. |
| Provisioning | `ProvisioningStepRunner.create_tenant` (the "Tenant, keys & backups" step) creates the DEK in the same transaction, before any patient row exists. |
| KMS key rotation | `TenantKeyRotationJob` (`app.kms.rotation.enabled=true`, `max-age-days` 365) rotates a region key that is older than the limit and re-wraps every DEK (`wrapped_dek` and `kms_key_ref` change in one statement, `rotated_at` set); reads keep working throughout. |
| DEK rotation | `TenantKeyService.rotateDek(tenant)`: new active version, old one `retired` (still decrypts); resets `crypto_reencrypt_progress` so `CryptoReencryptJob` rewrites rows under the new version. |
| Crypto-shred | `TenantKeyService.shred(tenant, approverA, approverB)`: offboarded tenants only, two distinct approvers; empties the wrapped DEKs, tells the KMS to refuse the tenant (so a restored backup stays unreadable) and clears this replica's cache. Other replicas lose the key within 15 minutes. Values still under the global keyring are **not** covered: run `CryptoReencryptJob.verify()` first. No HTTP endpoint exists on purpose (irreversible); the dual-approval workflow and the offboarding hook are not built. |
| KMS outage | Cached DEKs work for up to 15 minutes; after that decryption fails with `503 TENANT_KEY_UNAVAILABLE` and the alert counter `nabd_tenant_key_unavailable_total` increments (plus an ERROR log line starting `ALERT tenant key unavailable`). |
| Regions screen | `GET /v1/platform/territories` returns `kmsKeyCreatedAt` / `kmsKeyRotatedAt` per region; the console shows key age and last rotation. |
