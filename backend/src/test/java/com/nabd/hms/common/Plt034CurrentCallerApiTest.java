package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-034: @CurrentTenant/@CurrentStaff resolution, and a newly @Valid body refused as a 400 problem. */
class Plt034CurrentCallerApiTest extends ApiTestBase {

    @Test
    void newlyValidatedBodyBreakingItsConstraintIs400Problem() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "valid@plt034.test", "+919000034051", false);
        // DeclineBody.note is @Size(max = 280); before PLT-034 the constraint was not enforced (no @Valid)
        ResponseEntity<Map> r = http.postForEntity(url("/v1/booking-requests/" + UUID.randomUUID() + "/decline"),
                authedJsonBody(loginAndGetAccessToken(s), Map.of("reasonCode", "other", "note", "x".repeat(281))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("type")).asString().endsWith("validation-failed");
        assertThat((List<?>) r.getBody().get("errors")).anySatisfy(e -> assertThat(((Map<?, ?>) e).get("field")).isEqualTo("note"));
    }

    @Test
    void currentTenantResolvesFromTheSignedInToken() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "caller@plt034.test", "+919000034052", false);
        ResponseEntity<List> r = exchange("/v1/auth/sessions", HttpMethod.GET, authed(loginAndGetAccessToken(s)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
    }

    @Test
    void resolverRefusesATokenWithoutTheClaim() {
        UUID staff = UUID.randomUUID(), tenant = UUID.randomUUID();
        Jwt withTenant = Jwt.withTokenValue("t").header("alg", "none").subject(staff.toString()).claim("tenantId", tenant.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Jwt operator = Jwt.withTokenValue("t").header("alg", "none").subject(staff.toString()).claim("role", "support")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(CurrentCallerResolver.resolve(new TestingAuthenticationToken(withTenant, null), true)).isEqualTo(tenant);
        assertThat(CurrentCallerResolver.resolve(new TestingAuthenticationToken(withTenant, null), false)).isEqualTo(staff);
        assertThatThrownBy(() -> CurrentCallerResolver.resolve(new TestingAuthenticationToken(operator, null), true))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> CurrentCallerResolver.resolve(null, false)).isInstanceOf(AccessDeniedException.class);
    }
}
