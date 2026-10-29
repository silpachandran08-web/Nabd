package com.nabd.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * One shared Spring context for every Edge test: real Postgres 16 (Testcontainers; the app connects as the non-owner role
 * edge_app so FORCE RLS really applies, Flyway runs as the superuser), a Redis 6.2 stand-in for Valkey, fake Core and Comms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "edge.booking-flow=false")
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EdgeTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("edge_test");
    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final FakeUpstreams UP = new FakeUpstreams();
    /** Generated at test time; never a real key. */
    static final String BUTTON_KEY;

    static {
        POSTGRES.start();
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        BUTTON_KEY = Base64.getEncoder().encodeToString(k);
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var s = c.createStatement()) {
            s.execute("create role edge_app login password 'edge_app_pw'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "edge_app");
        r.add("spring.datasource.password", () -> "edge_app_pw");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.data.redis.url", VALKEY::url);
        r.add("management.server.port", () -> "0");
        r.add("edge.core-url", UP::coreUrl);
        r.add("edge.comms-url", UP::commsUrl);
        r.add("edge.button-key", () -> BUTTON_KEY);
        r.add("edge.ip-per-min", () -> "30");
        r.add("edge.notice-base-url", () -> "https://notice.example.test");
    }

    @Autowired StringRedisTemplate valkey;
    @Autowired(required = false) CountingHandler counting;
    @Autowired DataSource dataSource;
    @Autowired EdgeProperties props;

    @BeforeEach
    void clean() {
        UP.sent.clear();
        UP.slotsByDoctor.clear();
        UP.coreDelayMs = 0;
        UP.coreCalls.set(0);
        UP.relayed.clear();
        UP.checkIns.clear();
        UP.checkInReplies.clear();
        UP.consentStatus = 201;
        UP.coreHook = null;
        UP.coreLog.clear();
        UP.noticeVersion = "v1";
        if (counting != null) {
            counting.invocations.set(0);
        }
        UP.profiles.clear();
        UP.households.clear();
        UP.rawSessions.clear();
        UP.corePaths.clear();
        UP.bookCalls.clear();
        UP.dependantCalls.clear();
        UP.bookResults.clear();
        UP.bookMode = "confirmed";
        UP.dependantStatus = 201;
        valkey.keys("edge:avail:*").forEach(valkey::delete);
    }

    /** Superuser connection: sees every row (the app role is RLS-restricted). */
    JdbcTemplate superuser() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    void publish(String stream, String type, UUID tenant, Map<String, String> data) {
        StringBuilder d = new StringBuilder("{\"tenantId\":\"" + tenant + "\"");
        data.forEach((k, v) -> d.append(",\"").append(k).append("\":\"").append(v).append("\""));
        String ce = "{\"specversion\":\"1.0\",\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"tenantid\":\"" + tenant
                + "\",\"data\":" + d + "}}";
        valkey.opsForStream().add(StreamRecords.string(Map.of("ce", ce)).withStreamKey(stream));
    }

    static void eventually(int millis, java.util.function.BooleanSupplier cond) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("condition not met within " + millis + " ms");
    }
}
