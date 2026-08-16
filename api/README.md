# Nabd HMS — API Standards & Contract (NB-005)

Stack: Next.js (BFF-facing client) → Spring Boot (Java) services → PostgreSQL
(RLS-based multi-tenancy per NB-002). Contract-first: `openapi.yaml` is the
source of truth; Java controllers are generated/validated against it in CI
(`openapi-generator` + a spec-lint step), matching NB-005's acceptance
criteria.

## Conventions

- **Versioning**: URI-versioned, `/v1/...`. Breaking changes bump the prefix;
  additive changes (new optional field, new endpoint) don't.
- **Errors**: [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807)
  `application/problem+json` on every non-2xx response. See `Problem` schema.
  Field-level validation errors go in `errors[]`.
- **Pagination**: cursor-based (`limit` + `cursor` query params, opaque
  base64 cursor), not offset — offset pagination falls over on the patient
  table at clinic scale (NB-061 targets p95 < 300ms on 200k patients).
- **Idempotency**: any POST that creates money movement or a clinical event
  with side effects (invoices, payments, refunds, package sales) requires an
  `Idempotency-Key` header; server stores the key→response mapping for 24h
  and replays the original response on retry. Not yet wired into this pass
  (billing endpoints land in a later phase) — the header is reserved here so
  the convention doesn't get invented twice.
- **JSON casing**: camelCase (matches Jackson defaults, avoids a mapping
  layer in Spring).

## Security model

- **AuthN**: OAuth2-style bearer JWT access token (short-lived, ~15 min) +
  rotating opaque refresh token (NB-032). Refresh-token reuse is treated as
  compromise and revokes the whole session family.
- **Tenant scoping**: `tenantId` is a JWT claim, never a client-supplied
  header or path segment, for every tenant-scoped route. Trusting a client
  value here is exactly the cross-tenant IDOR class NB-050/NB-142 exist to
  close — the gateway rejects any request whose token doesn't carry it, and
  Postgres RLS enforces it a second time at the data layer (defense in
  depth, not either/or).
- **AuthZ**: deny-by-default. Every operation below declares an
  `x-required-permission` (`module:action`, matching the RBAC Matrix tab —
  e.g. `patients:edit`, `billing:refund`). Enforced server-side only; the
  UI hiding a button is not a control (NB-043).
- **Row-level scoping**: "own patients only" doctors get a 404 (not 403) on
  another doctor's patient ID — a 403 confirms the record exists, a 404
  doesn't (NB-041).
- **Step-up auth**: endpoints marked `x-step-up: true` (role changes, staff
  suspension) require a fresh MFA assertion (`X-Step-Up-Token`, issued by
  `/v1/auth/mfa/verify`, 5 min TTL) even on an already-authenticated session.
- **Rate limiting**: every response carries `X-RateLimit-Limit` /
  `-Remaining` / `-Reset`; auth and OTP endpoints have tighter per-IP limits
  (NB-143).

## What's in this pass vs. deferred

This first spec covers the P1/P2 foundation layer everything else in the
backlog depends on: Auth & session (E04), RBAC/staff (E05), minimal tenant/
branch (E06), core Patient records (E07), and read-only Audit (E19).

Deferred to follow-up passes, each depends on this layer being stable first:
Queue & Appointments (E08), Clinical Workspace (E09-E10), Packages (E11),
Billing/Tax/Payments (E12) — this is where `Idempotency-Key` actually gets
used — Reports (E13), Integrations (E14). Say which epic to spec next.






Login logic and testing:-


❯ Explain the current code flow like explaining to a fresher. Also suggest ways on how to test it on my own                                                                        

⏺ The code flow, from a fresh request to a token
  
  The shape of the code

  Four buckets, by responsibility:

  AuthController   → HTTP in/out only. Reads the request, calls AuthService, returns the response.
  AuthService      → All the business logic. The 10-step flow lives here.
  AuthRepository   → All the SQL. AuthService never writes SQL directly.
  JwtConfig / SecurityConfig / AesGcmCipher / TotpService → supporting machinery.

  The rule of thumb: Controller doesn't think, Service doesn't do SQL, Repository doesn't know about HTTP. Each layer only talks to the one below it.

  Walkthrough: a plain login (no MFA)

  A request hits POST /v1/auth/login with {tenantSlug, email, password}.

  1. AuthController.login() — pulls the caller's IP and User-Agent out of the raw HttpServletRequest, hands everything to AuthService.login(...), and returns whatever comes back
  wrapped in ResponseEntity.ok(...). It doesn't know if that's a token pair or an MFA challenge — it just relays the Object the service gives it. This is why the OpenAPI spec
  models the response as oneOf: [TokenPair, MfaChallenge].

  2. AuthService.login() runs, in order:

  - enforceIpRateLimit(ip) — counts rows in login_attempts from this IP in the last minute. Too many → throw 429.
  - repo.findTenantBySlug("clinic-a") — the tenants table has no RLS, on purpose: at this point we don't know which tenant we're even talking to, so there's nothing to scope by
  yet.
  - repo.setTenantContext(tenant.id()) — this is the important line. It runs SELECT set_config('app.tenant_id', '<uuid>', true) on the current DB connection. Postgres remembers
  that value for the rest of this transaction. Every table below this line has a Row-Level-Security policy that says "you may only see rows where tenant_id equals whatever
  app.tenant_id is currently set to." Skip this call, and every subsequent query silently returns zero rows (or, as we found while testing, throws if the value happens to be a
  stale empty string — more on that below).
  - repo.findStaffByEmail(tenantId, email) — now that context is set, this query is filtered twice: once explicitly in the WHERE tenant_id = ?, and once again invisibly by
  Postgres's RLS policy. Belt and suspenders.
  - If staff doesn't exist, or isn't active → record a failed attempt, throw a generic 401. Same 401, same JSON shape, whether the email doesn't exist or the password is wrong —
  that's the anti-enumeration measure from the login-steps list.
  - enforceAccountLockout(...) — counts failed attempts in login_attempts since the last successful one for this staff member. Five or more, and it's still inside the backoff
  window → 423 Locked, without even looking at the password.
  - passwordEncoder.matches(rawPassword, staff.passwordHash()) — this is Argon2id, a hashing algorithm specifically designed to be slow and memory-hungry, so that even if someone
  steals the whole staff table, brute-forcing passwords out of the hashes is expensive. Wrong password → record failure, 401.
  - Password correct → record a successful attempt (this is what resets the lockout window — there's no separate "failed attempt counter" column; it's computed on the fly from
  this log every time).
  - staff.mfaEnabled() is false → straight to mintTokenPair(...).

  3. mintTokenPair() — the heart of "issue a token":
  - Looks up the staff's role, reads its grants JSON column ([{"module":"patients","view":true,...}]), and flattens it into a flat list like ["patients:view", "patients:create", 
  "billing:refundDiscount"]. That list gets baked directly into the JWT as a permissions claim — so any future endpoint can check "does this token's permissions list contain
  patients:edit?" without a database round-trip.
  - Builds a JwtClaimsSet (subject = staffId, plus tenantId, roleId, sid = a new session id, permissions) and calls jwtEncoder.encode(...). This is Spring's built-in
  NimbusJwtEncoder, signing with an RSA private key generated once at boot (JwtConfig). The result is the three-dot-separated header.payload.signature string you'd recognize as a
  JWT.
  - Generates a separate, random 32-byte refresh token (SecureRandom, not derived from anything). Only its SHA-256 hash goes into the sessions table — the raw value is returned to
  the client once and never stored anywhere.
  - Inserts a row into sessions: who, which "family" (explained below), the token hash, expiry, device/IP.
  - Returns {accessToken, refreshToken, expiresIn}.

  That's a plain login: one HTTP round trip, one transaction, one token pair out.

  Walkthrough: MFA-enabled login

  Same as above right up until staff.mfaEnabled() is true. Instead of minting tokens, issueMfaChallenge(staff) runs: it builds a different JWT — subject = staffId, purpose: 
  "mfa_pending", tenantId, expiring in 5 minutes — and returns that as challengeId. No database row is created for this. The JWT itself, signed and time-boxed, is the "challenge
  state." That's deliberately lazy: instead of a challenges table with cleanup jobs, the JWT's own expiry claim does the cleanup for free.

  The client then calls POST /v1/auth/mfa/verify with {challengeId, code}:
  - jwtDecoder.decode(challengeId) — verifies the signature and expiry. If it's expired or tampered with, this throws by itself; we don't have to check a timestamp manually.
  - Checks purpose == "mfa_pending" — so a normal access token can't be replayed here.
  - Reads tenantId straight off the challenge claims and sets RLS context before touching staff — this ordering is exactly the bug we hit and fixed (below).
  models the response as oneOf: [TokenPair, MfaChallenge].

  2. AuthService.login() runs, in order:

  - enforceIpRateLimit(ip) — counts rows in login_attempts from this IP in the last minute. Too many → throw 429.
  - repo.findTenantBySlug("clinic-a") — the tenants table has no RLS, on purpose: at this point we don't know which tenant we're even talking to, so there's nothing to scope by
  yet.
  - repo.setTenantContext(tenant.id()) — this is the important line. It runs SELECT set_config('app.tenant_id', '<uuid>', true) on the current DB connection. Postgres remembers
  that value for the rest of this transaction. Every table below this line has a Row-Level-Security policy that says "you may only see rows where tenant_id equals whatever
  app.tenant_id is currently set to." Skip this call, and every subsequent query silently returns zero rows (or, as we found while testing, throws if the value happens to be a
  stale empty string — more on that below).
  - repo.findStaffByEmail(tenantId, email) — now that context is set, this query is filtered twice: once explicitly in the WHERE tenant_id = ?, and once again invisibly by
  Postgres's RLS policy. Belt and suspenders.
  - If staff doesn't exist, or isn't active → record a failed attempt, throw a generic 401. Same 401, same JSON shape, whether the email doesn't exist or the password is wrong —
  that's the anti-enumeration measure from the login-steps list.
  - enforceAccountLockout(...) — counts failed attempts in login_attempts since the last successful one for this staff member. Five or more, and it's still inside the backoff
  window → 423 Locked, without even looking at the password.
  - passwordEncoder.matches(rawPassword, staff.passwordHash()) — this is Argon2id, a hashing algorithm specifically designed to be slow and memory-hungry, so that even if someone
  steals the whole staff table, brute-forcing passwords out of the hashes is expensive. Wrong password → record failure, 401.
  - Password correct → record a successful attempt (this is what resets the lockout window — there's no separate "failed attempt counter" column; it's computed on the fly from
  this log every time).
  - staff.mfaEnabled() is false → straight to mintTokenPair(...).

  3. mintTokenPair() — the heart of "issue a token":
  - Looks up the staff's role, reads its grants JSON column ([{"module":"patients","view":true,...}]), and flattens it into a flat list like ["patients:view", "patients:create",
  "billing:refundDiscount"]. That list gets baked directly into the JWT as a permissions claim — so any future endpoint can check "does this token's permissions list contain
  patients:edit?" without a database round-trip.
  - Builds a JwtClaimsSet (subject = staffId, plus tenantId, roleId, sid = a new session id, permissions) and calls jwtEncoder.encode(...). This is Spring's built-in
  NimbusJwtEncoder, signing with an RSA private key generated once at boot (JwtConfig). The result is the three-dot-separated header.payload.signature string you'd recognize as a
  JWT.
  - Generates a separate, random 32-byte refresh token (SecureRandom, not derived from anything). Only its SHA-256 hash goes into the sessions table — the raw value is returned to
  the client once and never stored anywhere.
  - Inserts a row into sessions: who, which "family" (explained below), the token hash, expiry, device/IP.
  - Returns {accessToken, refreshToken, expiresIn}.

  Why refresh and mfa/verify needed special-casing: both of them start with only an opaque token or a staffId, with no tenant known yet — but the tables they need to read
  (sessions, staff) are RLS-protected by tenant. For MFA, the fix was easy: we already know the tenant when we issue the challenge, so we just put it in the JWT claim. For
  refresh, there's no such claim available (raw refresh tokens are just random bytes, not a JWT — intentionally, so nothing can be learned from a stolen token besides its hash),
  so instead we use a Postgres SECURITY DEFINER function: a stored procedure that runs with elevated privilege for that one narrow lookup, on the theory that possessing the 
  unguessable 256-bit token is itself the proof of authorization — same trust model as any bearer token.

  The @Transactional rollback gotcha: when a Java exception (any RuntimeException, which includes our ApiException) propagates out of a @Transactional method, Spring's default
  behavior is to roll back everything that method did — including database writes you made on purpose just before throwing. We wrote a failed-attempt row, then threw "invalid
  credentials" right after, and Spring quietly undid the write. Lockout looked implemented but silently did nothing until we added noRollbackFor = ApiException.class. This is a
  very easy trap to fall into and a good one to remember for any future "log then reject" pattern.

  ---
  How to test it yourself
  
  1. The cheap, automated check — no database needed

  cd backend
  mvn test
  
  This runs TotpServiceTest, which checks our TOTP implementation against the official RFC 6238 test vector. Green means the crypto math is right. This is the one you'd wire into
  CI.

  2. Manual end-to-end testing against a real Postgres

  This is the good part — you get to watch the actual flow happen.

  a) Start Postgres and create a database:
  createdb nabd_dev

  b) Apply the schema:
  psql -d nabd_dev -f db/migrations/V1__staff_auth_core.sql

  c) Create a non-superuser app role (RLS is bypassed by table owners/superusers, so if you connect as postgres you won't actually be testing tenant isolation):
  psql -d nabd_dev
  CREATE ROLE nabd_app LOGIN PASSWORD 'nabd_app';
  GRANT USAGE ON SCHEMA public TO nabd_app;
  GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO nabd_app;
  GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nabd_app;
  GRANT EXECUTE ON FUNCTION find_session_by_token_hash(text) TO nabd_app;

  d) Seed one tenant/role/staff by hand (there's no register/invite endpoint yet, so this stands in for it):
  INSERT INTO tenants (id, slug, name, region, status) VALUES
    (gen_random_uuid(), 'clinic-a', 'Clinic A', 'IN', 'active');
    
  models the response as oneOf: [TokenPair, MfaChallenge].

  2. AuthService.login() runs, in order:

  - enforceIpRateLimit(ip) — counts rows in login_attempts from this IP in the last minute. Too many → throw 429.
  - repo.findTenantBySlug("clinic-a") — the tenants table has no RLS, on purpose: at this point we don't know which tenant we're even talking to, so there's nothing to scope by
  yet.
  - repo.setTenantContext(tenant.id()) — this is the important line. It runs SELECT set_config('app.tenant_id', '<uuid>', true) on the current DB connection. Postgres remembers
  that value for the rest of this transaction. Every table below this line has a Row-Level-Security policy that says "you may only see rows where tenant_id equals whatever
  app.tenant_id is currently set to." Skip this call, and every subsequent query silently returns zero rows (or, as we found while testing, throws if the value happens to be a
  stale empty string — more on that below).
  - repo.findStaffByEmail(tenantId, email) — now that context is set, this query is filtered twice: once explicitly in the WHERE tenant_id = ?, and once again invisibly by
  Postgres's RLS policy. Belt and suspenders.
  - If staff doesn't exist, or isn't active → record a failed attempt, throw a generic 401. Same 401, same JSON shape, whether the email doesn't exist or the password is wrong —
  that's the anti-enumeration measure from the login-steps list.
  - enforceAccountLockout(...) — counts failed attempts in login_attempts since the last successful one for this staff member. Five or more, and it's still inside the backoff
  window → 423 Locked, without even looking at the password.
  - passwordEncoder.matches(rawPassword, staff.passwordHash()) — this is Argon2id, a hashing algorithm specifically designed to be slow and memory-hungry, so that even if someone
  steals the whole staff table, brute-forcing passwords out of the hashes is expensive. Wrong password → record failure, 401.
  - Password correct → record a successful attempt (this is what resets the lockout window — there's no separate "failed attempt counter" column; it's computed on the fly from
  this log every time).
  - staff.mfaEnabled() is false → straight to mintTokenPair(...).

  3. mintTokenPair() — the heart of "issue a token":
  - Looks up the staff's role, reads its grants JSON column ([{"module":"patients","view":true,...}]), and flattens it into a flat list like ["patients:view", "patients:create",
  "billing:refundDiscount"]. That list gets baked directly into the JWT as a permissions claim — so any future endpoint can check "does this token's permissions list contain
  patients:edit?" without a database round-trip.
  - Builds a JwtClaimsSet (subject = staffId, plus tenantId, roleId, sid = a new session id, permissions) and calls jwtEncoder.encode(...). This is Spring's built-in
  NimbusJwtEncoder, signing with an RSA private key generated once at boot (JwtConfig). The result is the three-dot-separated header.payload.signature string you'd recognize as a
  JWT.
  - Generates a separate, random 32-byte refresh token (SecureRandom, not derived from anything). Only its SHA-256 hash goes into the sessions table — the raw value is returned to
  the client once and never stored anywhere.
  - Inserts a row into sessions: who, which "family" (explained below), the token hash, expiry, device/IP.
  - Returns {accessToken, refreshToken, expiresIn}.

  That's a plain login: one HTTP round trip, one transaction, one token pair out. 
  
  Walkthrough: MFA-enabled login

  Same as above right up until staff.mfaEnabled() is true. Instead of minting tokens, issueMfaChallenge(staff) runs: it builds a different JWT — subject = staffId, purpose: 
  "mfa_pending", tenantId, expiring in 5 minutes — and returns that as challengeId. No database row is created for this. The JWT itself, signed and time-boxed, is the "challenge
  state." That's deliberately lazy: instead of a challenges table with cleanup jobs, the JWT's own expiry claim does the cleanup for free.
  
  The client then calls POST /v1/auth/mfa/verify with {challengeId, code}:
  - jwtDecoder.decode(challengeId) — verifies the signature and expiry. If it's expired or tampered with, this throws by itself; we don't have to check a timestamp manually.
  - Checks purpose == "mfa_pending" — so a normal access token can't be replayed here.
  - Reads tenantId straight off the challenge claims and sets RLS context before touching staff — this ordering is exactly the bug we hit and fixed (below).
  - Decrypts the staff's TOTP secret (AesGcmCipher.decrypt) and checks the 6-digit code against it (TotpService.verify) — this is the standard "Google Authenticator" algorithm
  (RFC 6238): hash the current 30-second time window with the secret, truncate to 6 digits, compare.
  - Match → mintTokenPair(...), same as the plain-login path.
  
  Walkthrough: refresh (and reuse detection)
  
  This is the part most fresh engineers haven't seen before, so it's worth slowing down on.
  
  Every refresh token belongs to a family — a chain of tokens that all trace back to one original login. Each row in sessions is one link in that chain:
  
  login          → row A  (family=F, active)
  refresh with A → row A revoked("rotated"), row B created (family=F, active)
  refresh with B → row B revoked("rotated"), row C created (family=F, active)

  POST /v1/auth/refresh with {refreshToken}:
  - Hash the presented token, look up its row via find_session_by_token_hash(...) — a Postgres function, not a plain SELECT, because of the same "don't know the tenant yet"
  problem as MFA (details below).
  - If that row's revoked_at is already set, that means this exact refresh token was already used once before — but here it is again. That can only happen if someone stole the
  token and is racing the legitimate owner. The response: revoke every still-active row in that entire family, not just this one — logging every device that chain was ever handed
  to out at once. This is why the client should treat a 401 on refresh as "go back to login," not "just retry."
  - If the row is still active and not expired: revoke this row with reason "rotated", and mint a brand-new pair with the same family_id (so the chain continues) but a new token
  hash.

  Walkthrough: logout / list sessions

  These are the two endpoints that require a real access token (@AuthenticationPrincipal Jwt jwt in the controller). The access token already carries tenantId, sub (staffId) and
  sid (the current session's row id) as claims — so the controller just reads those straight out of the verified, signed token and passes them to the service. No extra DB lookup
  needed to know "who is this and which session are they using."

  Two concepts worth slowing down on

  Row-Level Security (RLS) is a Postgres feature, not an application-layer thing. Even if a bug in Java code forgot to filter by tenant_id, the database itself refuses to return
  rows from another tenant — as long as app.tenant_id has been set correctly for that connection. Think of it as a second guard standing behind the first one. The catch we hit
  while testing: Postgres connection pools (HikariCP here) reuse the same physical connection across unrelated requests. If request #1 sets app.tenant_id and commits, and request
  #2 forgets to set it before querying, Postgres doesn't return "nothing" — it can return a leftover empty string, which then fails to cast to uuid and blows up with a confusing
  error. The fix is discipline: always call setTenantContext(...) as the very first thing after you learn the tenant, before touching any RLS-protected table.

  Why refresh and mfa/verify needed special-casing: both of them start with only an opaque token or a staffId, with no tenant known yet — but the tables they need to read
  (sessions, staff) are RLS-protected by tenant. For MFA, the fix was easy: we already know the tenant when we issue the challenge, so we just put it in the JWT claim. For
  refresh, there's no such claim available (raw refresh tokens are just random bytes, not a JWT — intentionally, so nothing can be learned from a stolen token besides its hash),
  so instead we use a Postgres SECURITY DEFINER function: a stored procedure that runs with elevated privilege for that one narrow lookup, on the theory that possessing the
  unguessable 256-bit token is itself the proof of authorization — same trust model as any bearer token.

  The @Transactional rollback gotcha: when a Java exception (any RuntimeException, which includes our ApiException) propagates out of a @Transactional method, Spring's default
  behavior is to roll back everything that method did — including database writes you made on purpose just before throwing. We wrote a failed-attempt row, then threw "invalid
  credentials" right after, and Spring quietly undid the write. Lockout looked implemented but silently did nothing until we added noRollbackFor = ApiException.class. This is a
  very easy trap to fall into and a good one to remember for any future "log then reject" pattern.

  ---
  How to test it yourself

  1. The cheap, automated check — no database needed

  cd backend
  mvn test

  This runs TotpServiceTest, which checks our TOTP implementation against the official RFC 6238 test vector. Green means the crypto math is right. This is the one you'd wire into
  CI.

  2. Manual end-to-end testing against a real Postgres

  This is the good part — you get to watch the actual flow happen.

  a) Start Postgres and create a database:
  createdb nabd_dev

  b) Apply the schema:
  psql -d nabd_dev -f db/migrations/V1__staff_auth_core.sql

  c) Create a non-superuser app role (RLS is bypassed by table owners/superusers, so if you connect as postgres you won't actually be testing tenant isolation):
  psql -d nabd_dev
  CREATE ROLE nabd_app LOGIN PASSWORD 'nabd_app';
  GRANT USAGE ON SCHEMA public TO nabd_app;
  GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO nabd_app;
  GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nabd_app;
  GRANT EXECUTE ON FUNCTION find_session_by_token_hash(text) TO nabd_app;

  d) Seed one tenant/role/staff by hand (there's no register/invite endpoint yet, so this stands in for it):
  INSERT INTO tenants (id, slug, name, region, status) VALUES
    (gen_random_uuid(), 'clinic-a', 'Clinic A', 'IN', 'active');

  -- copy the generated tenant id into the next two inserts
  INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES
    (gen_random_uuid(), '<tenant-id>', 'Owner', true,
     '[{"module":"patients","view":true,"create":true}]');

  -- password is "Password123!" — hash below is a real Argon2id hash for it, safe to reuse for local testing
  INSERT INTO staff (id, tenant_id, role_id, email, name, password_hash, status, mfa_enabled) VALUES
    (gen_random_uuid(), '<tenant-id>', '<role-id>', 'owner@a.com', 'Owner A',
     '$argon2id$v=19$m=19456,t=2,p=1$Qi/sqMpWFY1uuiJL0OFexg$MMBK7MQK1t1NOXHawJN9AxkQYmqC92mBpqF/20sQKFc',
     'active', false);

  e) Run the app:
  cd backend
  DB_URL=jdbc:postgresql://localhost:5432/nabd_dev \
  DB_USER=nabd_app DB_PASSWORD=nabd_app \
  APP_CRYPTO_KEY=$(openssl rand -base64 32) \
  mvn spring-boot:run
  (APP_CRYPTO_KEY just needs to be some 32-byte base64 string — it boots fine with a fresh random one each time as long as you're not also testing MFA across restarts.)

  f) Hit it with curl:
  # successful login
  curl -s -X POST localhost:8080/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"tenantSlug":"clinic-a","email":"owner@a.com","password":"Password123!"}' | python3 -m json.tool

  # wrong password — should look identical in shape to "unknown email"
  curl -s -X POST localhost:8080/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"tenantSlug":"clinic-a","email":"owner@a.com","password":"wrong"}'

  # hammer it 6 times to see the lockout kick in (423 on the 6th)
  for i in $(seq 1 6); do
    curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/v1/auth/login \
      -H "Content-Type: application/json" \
      -d '{"tenantSlug":"clinic-a","email":"owner@a.com","password":"wrong"}'
  done

  g) Try the session endpoints (grab accessToken from a successful login response first):
  curl -s localhost:8080/v1/auth/sessions -H "Authorization: Bearer <accessToken>"
  curl -s -X POST localhost:8080/v1/auth/logout -H "Authorization: Bearer <accessToken>"

  h) Try refresh-token reuse detection — this is the fun one to watch fail correctly:
  REFRESH=<refreshToken from login>
  curl -s -X POST localhost:8080/v1/auth/refresh -d "{\"refreshToken\":\"$REFRESH\"}" -H "Content-Type: application/json"
  # copy the NEW refreshToken from that response, then...
  curl -s -X POST localhost:8080/v1/auth/refresh -d "{\"refreshToken\":\"$REFRESH\"}" -H "Content-Type: application/json"
  # ^ reusing the OLD one now: should 401
  curl -s -X POST localhost:8080/v1/auth/refresh -d "{\"refreshToken\":\"<the NEW one>\"}" -H "Content-Type: application/json"
  # ^ even the new one is now dead too — the whole family got revoked

  3. Tools that make this easier than raw curl

  - Postman or Insomnia: import the requests above as a collection, save the accessToken/refreshToken as environment variables that auto-update after each login/refresh call. Much
  less copy-pasting than curl.
  - jwt.io: paste any accessToken there to see its decoded claims (tenantId, roleId, permissions, sid) without writing any code — useful for eyeballing "did the right permissions
  end up in this token."
  - psql directly against the sessions table while you test refresh/reuse, so you can literally watch rows flip from active → revoked_reason='rotated' → and see a whole family go
  to 'reuse_detected' in one UPDATE. That's the best way to see the rotation-chain concept rather than just read about it.
  - DBeaver / TablePlus if you'd rather browse tables with a GUI than type psql queries.

  For MFA specifically, generating a real 6-digit code by hand needs a TOTP secret encrypted with whatever APP_CRYPTO_KEY you booted with — that's more setup than a first pass
  needs. I'd suggest skipping that path for now and coming back to it once there's a real "enroll MFA" endpoint that hands you a QR code, at which point you can just scan it into
  Google Authenticator like a normal user would
