package com.nabd.control;

import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One shared Spring context for every Control Plane test: real Postgres 16 (Testcontainers; Flyway runs as the superuser,
 * the app connects as the DML-only role control_app) and a Redis 6.2 stand-in for Valkey, as in the Edge tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ControlPlaneTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("control_test");
    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    static {
        POSTGRES.start();
        try (Connection c = superuserConnection("control_test"); var s = c.createStatement()) {
            s.execute("create role control_app login password 'control_app_pw'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "control_app");
        r.add("spring.datasource.password", () -> "control_app_pw");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.data.redis.url", VALKEY::url);
        r.add("management.server.port", () -> "0");
    }

    @Autowired StringRedisTemplate valkey;
    @Autowired JdbcTemplate jdbc;

    static String url(String database) {
        return POSTGRES.getJdbcUrl().replace("/control_test", "/" + database);
    }

    static Connection superuserConnection(String database) throws SQLException {
        return DriverManager.getConnection(url(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Superuser on the service's own database: for what control_app may not do. */
    static JdbcTemplate superuser() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
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
