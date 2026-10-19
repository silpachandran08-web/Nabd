package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-009: what the staff-web BFF needs from Core — the refresh expiry (cookie Max-Age) and family-wide logout. */
class Fef009SessionCookieContractApiTest extends ApiTestBase {

    private Map<?, ?> login(SeededStaff staff) {
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", staff.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    void tokenPairCarriesTheRefreshExpiryInSeconds() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "fef9-ttl@a.com", "+919000009001", false);
        Map<?, ?> body = login(staff);
        long thirtyDays = 30L * 24 * 3600;
        assertThat(((Number) body.get("refreshExpiresIn")).longValue()).isBetween(thirtyDays - 5, thirtyDays);
        assertThat(((Number) body.get("expiresIn")).longValue()).isLessThan(((Number) body.get("refreshExpiresIn")).longValue());

        // a rotated pair carries it too
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", body.get("refreshToken"))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) r.getBody().get("refreshExpiresIn")).longValue()).isBetween(thirtyDays - 5, thirtyDays);
    }

    @Test
    void logoutRevokesEveryActiveSessionOfTheFamilyButNotOtherLogins() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "fef9-family@a.com", "+919000009002", false);
        Map<?, ?> mine = login(staff);
        Map<?, ?> otherDevice = login(staff); // separate family
        String access = (String) mine.get("accessToken");
        UUID sid = sid(access);

        // A sibling row still active in the same family (e.g. a refresh racing the sign-out).
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        UUID sibling = UUID.randomUUID();
        su.update("INSERT INTO sessions (id, tenant_id, staff_id, family_id, token_hash, expires_at, device_label, ip_address, user_agent) " +
                "SELECT ?, tenant_id, staff_id, family_id, md5(random()::text), expires_at, device_label, ip_address, user_agent FROM sessions WHERE id = ?", sibling, sid);

        ResponseEntity<Void> out = exchange("/v1/auth/logout", HttpMethod.POST, authed(access), Void.class);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Integer activeInFamily = su.queryForObject("SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND family_id = (SELECT family_id FROM sessions WHERE id = ?)", Integer.class, sid);
        assertThat(activeInFamily).isZero();
        assertThat(su.queryForObject("SELECT revoked_reason FROM sessions WHERE id = ?", String.class, sibling)).isEqualTo("logout");

        // the other login (another family) is untouched
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", otherDevice.get("refreshToken"))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // and the logged-out refresh token is dead
        ResponseEntity<Map> dead = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", mine.get("refreshToken"))), Map.class);
        assertThat(dead.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private UUID sid(String accessToken) {
        try {
            Map<?, ?> claims = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), Map.class);
            return UUID.fromString((String) claims.get("sid"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
