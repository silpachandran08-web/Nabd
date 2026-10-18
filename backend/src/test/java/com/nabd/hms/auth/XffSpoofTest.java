package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XffSpoofTest extends Plt003Support {

    private HttpEntity<Map<String, String>> withXff(String xff, Map<String, String> body, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Forwarded-For", xff);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return new HttpEntity<>(body, h);
    }

    private String breakGlassAuditIp(SeededStaff s) {
        return inTenantTx(s.tenantId(), () -> jdbc.queryForObject(
                "SELECT host(ip_address) FROM audit_log WHERE action = 'auth.break_glass_activated' AND actor_id = ?", String.class, s.id()));
    }

    @Test
    void spoofedHeaderFromAnUntrustedHopIsIgnoredForRateLimitAndAudit() {
        SeededStaff s = newDoctor("xff@a.com", "+919300000031");
        String token = loginAndGetAccessToken(s);

        // login attempt rows (what the per-IP rate limit counts) carry the peer, not 1.2.3.4
        http.exchange(url("/v1/auth/login"), HttpMethod.POST,
                withXff("1.2.3.4", Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", "000000"), null), Map.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempts WHERE host(ip_address) = '1.2.3.4'", Integer.class)).isZero();

        // audit_log.ip_address likewise (break-glass activation writes an audit row with the request ip)
        HttpHeaders xff = new HttpHeaders();
        xff.add("X-Forwarded-For", "1.2.3.4");
        ResponseEntity<Map> bg = activateBreakGlass(token, "xff spoof test reason", xff);
        assertThat(bg.getStatusCode()).isEqualTo(HttpStatus.OK);
        String auditIp = breakGlassAuditIp(s);
        assertThat(auditIp).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").isNotEqualTo("1.2.3.4");
    }

    @Test
    void malformedHeaderNeverCauses500AndStoresTheValidatedPeerIp() {
        SeededStaff s = newDoctor("xff-bad@a.com", "+919300000032");
        String token = loginAndGetAccessToken(s);

        for (String junk : new String[]{"not-an-ip", "999.1.1.1", "1.2.3.4, <script>", "'; drop table staff;--"}) {
            ResponseEntity<Map> login = http.exchange(url("/v1/auth/login"), HttpMethod.POST,
                    withXff(junk, Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", "000000"), null), Map.class);
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        HttpHeaders xff = new HttpHeaders();
        xff.add("X-Forwarded-For", "not-an-ip");
        ResponseEntity<Map> bg = activateBreakGlass(token, "xff spoof test reason", xff);
        assertThat(bg.getStatusCode()).isEqualTo(HttpStatus.OK);
        String auditIp = breakGlassAuditIp(s);
        assertThat(auditIp).isIn("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    }
}
