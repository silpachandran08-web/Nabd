package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Positive control: when the peer IS a configured trusted proxy, X-Forwarded-For is honoured (so the untrusted test is not vacuous). */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1")
class XffTrustedProxyS9QaTest extends ApiTestBase {

    private ResponseEntity<String> login(SeededStaff s, String xff) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Forwarded-For", xff);
        return http.exchange(url("/v1/auth/login"), HttpMethod.POST, new HttpEntity<>(
                "{\"tenantSlug\":\"" + s.slug() + "\",\"email\":\"" + s.email() + "\",\"pin\":\"999999\"}", h), String.class);
    }

    @Test
    void trustedHopsXffIsUsed_andGarbageFromATrustedHopStillNeverBreaksTheRequest() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa-xff-trusted@a.com", "+919700000010", false);
        assertThat(login(s, "203.0.113.7").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String junk : List.of("not-an-ip", "999.1.1.1", "'; drop table staff;--", "1.2.3.4, not-an-ip")) {
            assertThat(login(s, junk).getStatusCode()).as(junk).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        List<String> ips = new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT host(ip_address) FROM login_attempts WHERE tenant_id = ?", String.class, t.id());
        assertThat(ips).contains("203.0.113.7");
        assertThat(ips).hasSize(5); // every junk request was recorded with some valid inet, none 500'd
    }
}
