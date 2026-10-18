# JWT signing key rotation (PLT-002)

The API signs access tokens with RS256. Keys live in a JWKS JSON secret holding exactly 2 RSA private keys (2048 bits or more, each with a unique `kid`). Key 0 signs new tokens. Key 1 is the next or previous key: it is published at `GET /.well-known/jwks.json` and accepted for verification. The JWKS endpoint returns only the public parts.

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
The per-cell secret and its mount are created by IaC (`infra/security`, not in this repo) in the chosen secret manager (KMS-protected). The app side is ready: it only reads `JWT_KEYS_FILE` or `JWT_KEYS_JSON`.
