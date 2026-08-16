package com.nabd.hms.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.ModuleGrant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real HTTP calls against a real Postgres with RLS active — the app's tenant isolation, lockout
 * backoff, and session-reuse detection all live in SQL, so a mocked repository layer would verify
 * nothing about those (see AuthService.enforceAccountLockout's history for why this matters).
 * The Postgres container + migrations + nabd_app role are bootstrapped once per JVM in the static
 * block below (shared across every subclass); each test method seeds its own tenant so tests never
 * share mutable state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTestBase {

    protected static final String APP_ROLE = "nabd_app";
    protected static final String APP_ROLE_PW = "nabd_app";
    protected static final String STAFF_PIN = "1234";
    /** RFC 6238 test-vector-style fixed secret — deterministic so tests can compute the current code. */
    protected static final byte[] TOTP_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    protected static final String CRYPTO_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("nabd_test");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../db/migrations")
                .load()
                .migrate();
        bootstrapAppRole();
    }

    private static void bootstrapAppRole() {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE_PW + "'");
            st.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            st.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
            st.execute("GRANT EXECUTE ON FUNCTION find_session_by_token_hash(text) TO " + APP_ROLE);
            st.execute("GRANT EXECUTE ON FUNCTION find_staff_by_invite_token_hash(text) TO " + APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to bootstrap nabd_app role", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PW);
        registry.add("spring.flyway.enabled", () -> "false"); // migrated as superuser above, before grants existed
        registry.add("app.crypto.key", () -> CRYPTO_KEY_B64);
        // default 20/min is realistic for one browser, not for a suite of HTTP calls from one loopback IP
        registry.add("app.auth.rate-limit-per-ip-per-minute", () -> "100000");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate http;

    /**
     * The default HttpURLConnection-based request factory always streams the request body as of
     * Spring Framework 6.1 (setOutputStreaming/setBufferRequestBody became no-ops), which trips a
     * JDK bug: any POST that gets back a 401 response throws "cannot retry due to server
     * authentication, in streaming mode" (HttpURLConnection tries to replay the request for an
     * auth challenge and can't, since the body was already streamed). The JDK HttpClient-based
     * factory doesn't share this code path. Unrelated to anything under test; harmless to reset
     * on every test method.
     */
    @BeforeEach
    void useJdkHttpClientRequestFactory() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordEncoder pinEncoder;

    @Autowired
    protected AesGcmCipher cipher;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected com.nabd.hms.common.TenantContext tenantContext;

    @Autowired
    protected org.springframework.transaction.PlatformTransactionManager txManager;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected record SeededTenant(UUID id, String slug) {
    }

    protected record SeededStaff(UUID id, UUID tenantId, String slug, String email, UUID roleId) {
    }

    /**
     * RLS on every tenant-scoped table checks app.tenant_id, set via SET LOCAL (see TenantContext) —
     * that only survives for the lifetime of one transaction, and a bare JdbcTemplate.update() call
     * has no transaction of its own, so each call would silently grab a fresh, unscoped connection.
     * Mirrors exactly what every real service method does: tenantContext.set() then repo calls,
     * all inside one @Transactional boundary.
     */
    protected <T> T inTenantTx(UUID tenantId, java.util.function.Supplier<T> work) {
        org.springframework.transaction.support.TransactionTemplate tt =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        return tt.execute(status -> {
            tenantContext.set(tenantId);
            return work.get();
        });
    }

    protected void inTenantTx(UUID tenantId, Runnable work) {
        inTenantTx(tenantId, () -> {
            work.run();
            return null;
        });
    }

    protected SeededTenant seedTenant() {
        UUID id = UUID.randomUUID();
        String slug = "t" + id.toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO tenants (id, slug, name, region, status) VALUES (?,?,?,?,?)",
                id, slug, "Test Clinic " + slug, "IN", "active");
        return new SeededTenant(id, slug);
    }

    protected static ModuleGrant fullGrant(String module) {
        return new ModuleGrant(module, true, true, true, true, true, true, true);
    }

    /** Owner-style role: full grants on staff/patients/queue, matching every @PreAuthorize in the controllers. */
    protected UUID seedFullAccessRole(UUID tenantId) {
        return seedRole(tenantId, "Owner", true, fullGrant("staff"), fullGrant("patients"), fullGrant("queue"));
    }

    protected UUID seedRole(UUID tenantId, String name, boolean builtIn, ModuleGrant... grants) {
        UUID id = UUID.randomUUID();
        String json = writeJson(List.of(grants));
        inTenantTx(tenantId, () -> jdbc.update(
                "INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES (?,?,?,?,?::jsonb)",
                id, tenantId, name, builtIn, json));
        return id;
    }

    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled) {
        return seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, true, true);
    }

    /** NB-041's dual-channel gate blocks all patient-data endpoints unless both flags are true — see PatientService. */
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                     boolean emailVerified, boolean mobileVerified) {
        UUID id = UUID.randomUUID();
        byte[] mfaSecretEnc = mfaEnabled ? cipher.encrypt(TOTP_SECRET) : null;
        inTenantTx(tenant.id(), () -> jdbc.update("""
                INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status,
                                    email_verified, mobile_verified, mfa_enabled, mfa_secret_enc)
                VALUES (?,?,?,?,?,?,?,'active',?,?,?,?)
                """,
                id, tenant.id(), roleId, email, "Test Staff", mobilePhone, pinEncoder.encode(STAFF_PIN),
                emailVerified, mobileVerified, mfaEnabled, mfaSecretEnc));
        return new SeededStaff(id, tenant.id(), tenant.slug(), email, roleId);
    }

    /** Full login round-trip over real HTTP — exercises the same code path a real client hits. */
    protected String loginAndGetAccessToken(SeededStaff staff) {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", staff.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        if (resp.getStatusCode() != HttpStatus.OK) {
            throw new IllegalStateException("seeded login failed: " + resp.getStatusCode() + " " + resp.getBody());
        }
        return (String) resp.getBody().get("accessToken");
    }

    protected HttpEntity<String> jsonBody(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(writeJson(body), headers);
    }

    protected HttpEntity<String> authedJsonBody(String accessToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(writeJson(body), headers);
    }

    protected HttpEntity<Void> authed(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    protected HttpEntity<String> authedJsonBodyWithHeader(String accessToken, String headerName, String headerValue, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        if (headerValue != null) {
            headers.set(headerName, headerValue);
        }
        return new HttpEntity<>(writeJson(body), headers);
    }

    protected <T> ResponseEntity<T> exchange(String path, HttpMethod method, HttpEntity<?> entity, Class<T> responseType) {
        return http.exchange(url(path), method, entity, responseType);
    }

    /** Same RFC 6238 algorithm as TotpService.generate() — kept independent rather than reaching into a package-private method. */
    protected String currentTotpCode() {
        long counter = Instant.now().getEpochSecond() / 30;
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(TOTP_SECRET, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
