# Valkey in Nabd (PLT-008)

One Valkey connection for the whole API, owned by `com.nabd.hms.common.valkey`. Wave 2 stories (OPS-016 streams,
OPS-017, OPS-020 revocation cache) reuse these beans. Never add a second Redis/Valkey config.

## Configuration (`app.valkey.*`)

| Property | Env | Default | Notes |
|---|---|---|---|
| `app.valkey.url` | `VALKEY_URL` | empty | `redis://host:port/db` or `rediss://user:pass@host:port/db`. Empty = no Valkey. |
| `app.valkey.connect-timeout-ms` | | 500 | |
| `app.valkey.command-timeout-ms` | | 250 | |

- Under the `prod` profile a non-TLS URL (`redis://`) refuses to boot. TLS verifies the certificate and host name.
- Boot's own Redis auto-configuration is excluded in `application.yml` (it would connect to localhost:6379).
- Beans, created only when the URL is set (`ValkeyConfig`): `LettuceConnectionFactory valkeyConnectionFactory` and
  `StringRedisTemplate valkeyTemplate`. Add your own templates/listeners on top of `valkeyConnectionFactory`.
- The connection is lazy (nothing connects at boot) and commands fail at once while disconnected
  (`REJECT_COMMANDS`), so the app boots and serves when Valkey is down or unset.

## Rules for consumers

1. Inject `ObjectProvider<StringRedisTemplate>` (or the factory), never a hard dependency: the bean is absent when
   `VALKEY_URL` is unset (local dev, every test context that does not opt in).
2. Every call can fail: catch `RuntimeException` and degrade. Decide per feature whether that means fail open
   (rate limits) or fall back to Postgres (OTP limits, session revocation).
3. Cluster-safe keys: a Lua script or multi-key command touches keys of one hash slot only. Use a hash tag,
   e.g. `rl:user:{<tenantId>}:<userId>` next to `rl:tenant:{<tenantId>}`.
4. No PHI and no raw phone numbers, names, e-mails or IPs in keys or values. Use ids or a hash.
5. Every key has a TTL.

## Health and metrics

- Health: Boot's `redis` contributor (appears when the factory exists) on `/actuator/health`. It is not in the
  readiness group (`readinessState,db,outbox`), so a Valkey outage never takes pods out of the load balancer.
- `nabd_rate_limit_exceeded_total{dimension}` and `nabd_rate_limit_fail_open_total{dimension}` (dimension = tenant, user, ip).
- Alert rules: `infra/observability/rate-limit.rules.yml` (`NabdRateLimitFailOpen` fires on the first evaluation after a
  failed limiter call; `NabdTenantRateLimitedRepeatedly`). The ERROR log line `rate limits failing open` is also written,
  at most once a minute.

## Tests: `ValkeyTestSupport`

No Valkey image is available, so tests use `com.github.codemonstur:embedded-redis:1.4.3` (test scope, Redis 6.2:
Lua, Streams and XAUTOCLAIM work).

```java
class MyValkeyApiTest extends ApiTestBase {
    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();   // free port, no persistence

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) { r.add("app.valkey.url", VALKEY::url); }

    @AfterAll
    static void stopValkey() { VALKEY.close(); }

    @Test void outage() {
        VALKEY.stop();        // Valkey down: port refuses connections
        try { /* assert the fallback */ } finally { VALKEY.restart(); }   // same port, empty data
        // Lettuce reconnects in the background: poll until a call succeeds again (see RateLimitApiTest)
    }
}
```

Each class that sets `app.valkey.url` gets its own Spring context (the URL differs), so put all Valkey HTTP tests of a
story in one class. Plain unit tests can build the production factory directly:
`new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(VALKEY.url(), 500, 250), new MockEnvironment())`
(see `TokenBucketsTest`, which runs two of them as two replicas).

## Rate limits (`com.nabd.hms.shared.ratelimit`)

`RateLimitFilter` runs in the security chain right after bearer authentication, before the idempotency store.

| Dimension | Key | Default | Applies to |
|---|---|---|---|
| tenant | `rl:tenant:{<tenantId>}` | 600/min, burst 1,200; plan `master.plans.api_requests_per_minute` / `api_burst` wins | every call with a tenant token |
| user | `rl:user:{<tenantId>}:<sub>` (`rl:user:{<sub>}` without a tenant) | 120/min, burst 120 | every authenticated call |
| ip | `rl:ip:<sha256(ip) prefix>` | 20/min, burst 20 | anonymous calls (pre-auth, public) |
| phone (OTP) | Postgres `otp_send_counters` (PLT-006 `OtpSendLimiter`) | 3 / 15 min, 10 / day, 60 s gap | `/v1/auth/otp/request` (always 202), fails closed |

- Token buckets in one Lua script (`TokenBuckets`): all buckets of a request are checked and charged atomically on
  the server with the server's clock, so the limit holds across replicas. Over the limit: 429, Problem type
  `https://api.nabd.health/errors/rate_limited`, `Retry-After` header plus `retryAfterSeconds` and `dimension` members.
- Plan values are re-read per replica every `app.rate-limit.plan-refresh-seconds` (30), so a plan change applies
  within 60 s without a deploy. Editing plan quotas in the console is PLT-059.
- Exempt: `/v1/auth/otp/request`, `/v1/auth/otp/verify` (PLT-006 limits) and `/v1/webhooks/whatsapp` (signed by Meta).
- Valkey down: tenant/user/ip limits fail open; OTP limits are unaffected (Postgres).
- At most one `shared.rate_limit.exceeded.v1` outbox event per bucket per minute (tenant and user dimensions).
- The auth endpoints keep their own Postgres per-IP attempt limit (`app.auth.rate-limit-per-ip-per-minute`).
