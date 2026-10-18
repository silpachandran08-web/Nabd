package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-003 AC5: attempts recorded under a tenant count toward the per-IP limit, which runs before the tenant is known. */
class IpRateLimitS9FixBTest extends ApiTestBase {

    @Test
    void tenantScopedAttemptsFromThePeerTripThePerIpLimit() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "fixb-ip@a.com", "+919700000071", false);
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        try {
            su.update("INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded) "
                    + "SELECT ?, NULL, 'fixb-ratelimit-fill', ip::inet, false FROM generate_series(1,100001) g, (VALUES ('127.0.0.1'),('::1')) v(ip)", t.id());
            int code = http.postForEntity(url("/v1/auth/login"),
                    jsonBody(Map.of("tenantSlug", s.slug(), "email", "nobody@a.com", "pin", "999999")), String.class).getStatusCode().value();
            assertThat(code).isEqualTo(429);
        } finally {
            su.update("DELETE FROM login_attempts WHERE email = 'fixb-ratelimit-fill'");
        }
    }
}
