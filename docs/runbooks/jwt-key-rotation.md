# JWT signing key rotation (PLT-002, PLT-009)

The API signs access tokens with RS256. Keys live in a JWKS JSON secret holding exactly 2 RSA private keys (2048 bits or more, each with a unique `kid`). Key 0 signs new tokens. Key 1 is the next or previous key: it is published at `GET /.well-known/jwks.json` and accepted for verification. The JWKS endpoint returns only the public parts.

## Populations (PLT-009)
Four token populations, each with its own issuer and keys, so a token of one can never verify for another:

| Population | Issuer | Keys (secret) | Public keys |
|---|---|---|---|
| staff | `https://api.nabd.health/staff` | `JWT_KEYS_FILE` / `JWT_KEYS_JSON` (2 private keys) | `/.well-known/jwks/staff.json` (also `/.well-known/jwks.json`) |
| owner | `https://api.nabd.health/owner` | `JWT_OWNER_KEYS_FILE` / `JWT_OWNER_KEYS_JSON` | `/.well-known/jwks/owner.json` |
| platform (operators) | `https://api.nabd.health/platform` | `JWT_PLATFORM_KEYS_FILE` / `JWT_PLATFORM_KEYS_JSON` | `/.well-known/jwks/platform.json` |
| internal (services) | `https://api.nabd.health/internal` | `JWT_INTERNAL_JWKS_FILE` / `JWT_INTERNAL_JWKS_JSON`: PUBLIC keys of the calling services (1 to 4), Core never mints these | not published |

Related, not a token population: `RX_KEYS_FILE` / `RX_KEYS_JSON` (PHL-013) hold the EC P-256 keys that sign the prescription QR (1+ private keys with `kid`, first signs, all verify; public parts at `GET /v1/pharmacy/rx-verifications/keys`). Also required under prod; rotate by prepending a new key and keeping the old one for as long as printed prescriptions may still be presented.

Each prefix has its own filter chain: `/v1/platform/**` accepts only platform access tokens, `/internal/v1/**` only internal service tokens (plus platform tokens for the few operator-driven endpoints that live there), `/v1/owners/**` none (owner endpoints read their own challenge token), `/v1/**` only staff access tokens. Under `prod` the app refuses to boot unless all four are configured. Rotate each population on its own using the steps below; the other three are not touched. The internal keys are rotated by the owner of the calling service: add the new public key to the JWKS, roll Core, then let the service switch.

## Configuration (every replica must see the same secret)
- `JWT_KEYS_FILE` (`app.jwt.keys-file`): path of the mounted secret file. Preferred.
- `JWT_KEYS_JSON` (`app.jwt.keys-json`): the JWKS JSON itself, for environments that inject env vars.
- Under `SPRING_PROFILES_ACTIVE=prod` the app refuses to boot when neither is set (the error names `JWT_KEYS_FILE`). Without the prod profile it generates throwaway keys, so tokens die on restart.
- Never put the JWKS in git, logs or tickets.

## Rotation (every 90 days)
Access tokens live 15 minutes. Overlap rules below keep that valid.
1. Generate a new RSA-2048 key with a fresh `kid` (e.g. `openssl genrsa 2048` converted to JWK with your tooling).
2. Write a new secret version with order `[current, NEW]`. Roll out the restart of all replicas. Now every replica publishes and accepts NEW but still signs with current, so no replica ever sees an unknown kid.
3. Wait until all replicas run the new secret version (check `/.well-known/jwks.json` on each).
4. Write a new secret version with order `[NEW, current]`. Roll out. NEW signs; current is still accepted, so tokens signed before the roll stay valid until they expire (15 minutes).
5. Keep current in the secret for at least 1 hour (above the 15 minute access TTL), then retire it by the next rotation: step 1 replaces it with the next key.
6. Verify: sign in, call an authenticated endpoint on each replica, check no 401 spike.

Rollback: restore the previous secret version and restart; tokens signed by NEW are rejected only if NEW was dropped, so keep it as key 1.

## Infra wiring (Needs external action)
The per-cell secret and its mount are created by IaC (`infra/security`, not in this repo) in the chosen secret manager (KMS-protected). The app side is ready: it only reads the variables in the table above. The existing secret must gain the three new files (`owner-jwks.json`, `platform-jwks.json`, `internal-public-jwks.json`) before the next deploy; the manifests under `infra/prod-in` and `infra/staging-in` already point at them.
