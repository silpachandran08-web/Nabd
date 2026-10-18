package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9 independent QA of OPS-003 criteria 4 and 5 through the REAL HTTP stack (servlet filters, Spring Security, advice),
 * which the engineer's DbFailureAdviceTest does not exercise (it calls the advice directly). A throw-away controller
 * that exists only in this test's context executes a slow query as nabd_app.
 */
@Import(Ops003HttpS9QaTest.Cfg.class)
class Ops003HttpS9QaTest extends ApiTestBase {

    @TestConfiguration
    static class Cfg {
        @Bean
        S9QaSlowController s9QaSlowController(JdbcTemplate jdbc) {
            return new S9QaSlowController(jdbc);
        }
    }

    @RestController
    static class S9QaSlowController {
        private final JdbcTemplate jdbc;

        S9QaSlowController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/v1/s9qa/slow")
        String slow() {
            return jdbc.queryForObject("SELECT pg_sleep(7)::text", String.class);
        }
    }

    @Autowired
    HikariDataSource pool;

    @Test
    void aQueryOverFiveSecondsBecomes503ProblemJsonDbTimeoutWithoutLeakingSql() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "s9http@a.com", "+919000005001", false));
        long t0 = System.nanoTime();
        ResponseEntity<String> r = exchange("/v1/s9qa/slow", HttpMethod.GET, authed(token), String.class);
        double secs = (System.nanoTime() - t0) / 1e9;
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(String.valueOf(r.getHeaders().getContentType())).contains("application/problem+json");
        assertThat(r.getBody()).contains("\"code\":\"DB_TIMEOUT\"").contains("traceId").doesNotContain("pg_sleep").doesNotContain("SELECT").doesNotContain("57014");
        assertThat(r.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(secs).as("answered after the 5 s statement_timeout, not after the 7 s sleep").isBetween(4.5, 6.9);
    }

    @Test
    void anExhaustedPoolFailsAfterAboutThreeSecondsWith503NotAHangOrA500() throws Exception {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "s9pool@a.com", "+919000005002", false));
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            long t0 = System.nanoTime();
            ResponseEntity<String> r = exchange("/v1/staff", HttpMethod.GET, authed(token), String.class);
            double secs = (System.nanoTime() - t0) / 1e9;
            assertThat(r.getStatusCode().value()).as("body=" + r.getBody() + " secs=" + secs).isEqualTo(503);
            assertThat(secs).as("fails fast, not the 30 s Hikari default").isBetween(2.5, 8.0);
            assertThat(r.getBody()).contains("DB_UNAVAILABLE");
        } finally {
            for (Connection c : held) c.close();
        }
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(token), Map.class).getStatusCode().value()).as("recovers").isEqualTo(200);
    }

    @Test
    void anExhaustedPoolAlsoGives503OnAPublicLoginEndpoint() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "s9poolpub@a.com", "+919000005003", false);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            long t0 = System.nanoTime();
            ResponseEntity<String> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", t.slug(), "email", s.email(), "pin", STAFF_PIN)), String.class);
            assertThat(r.getStatusCode().value()).as("body=" + r.getBody() + " secs=" + (System.nanoTime() - t0) / 1e9).isEqualTo(503);
            assertThat((System.nanoTime() - t0) / 1e9).isLessThan(8.0);
        } finally {
            for (Connection c : held) c.close();
        }
    }
}
