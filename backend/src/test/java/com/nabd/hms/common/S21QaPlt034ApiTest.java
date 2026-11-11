package com.nabd.hms.common;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S21 QA (t1), PLT-034: over HTTP, a correctly signed staff token without the tenantId claim is refused 403 (not 500) on
 * endpoints that now use @CurrentTenant / @CurrentStaff; the newly enforced 280-char note on booking-request accept/decline;
 * malformed bodies are 400 problems; the moved auth flows (login, refresh, step-up target binding) still answer as before.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S21QaPlt034ApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    private record Caller(SeededTenant t, SeededStaff s, String token) {
    }

    private Caller caller(String tag) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa21-" + tag + "-" + t.slug() + "@plt034.test",
                "+9190" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Caller(t, s, loginAndGetAccessToken(s));
    }

    private Map<String, Object> claims(String access) throws Exception {
        return objectMapper.readValue(Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
    }

    /** Re-signs the caller's real access token claims with the server key, without tenantId. */
    private String withoutTenant(String access) throws Exception {
        Map<String, Object> c = claims(access);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(600))
                .subject((String) c.get("sub"));
        for (String k : List.of("roleId", "sid", "permissions", "tenantName", "tenantRegion", "staffName", "roleName", "owner", "pinChange")) {
            if (c.get(k) != null) b.claim(k, c.get(k));
        }
        TokenType.ACCESS.stamp(b, Audience.STAFF_API);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    @Test
    void p01_aSignedTokenWithoutTenantClaimIs403NotA500_onResolverEndpoints() throws Exception {
        Caller c = caller("notenant");
        String bad = withoutTenant(c.token());
        // sanity: the same re-signing with the tenant kept would be accepted (rules out a mock/signing mistake)
        for (String path : List.of("/v1/auth/sessions", "/v1/patients?limit=5", "/v1/booking-requests", "/v1/setup/subscription")) {
            ResponseEntity<String> r = exchange(path, HttpMethod.GET, authed(bad), String.class);
            assertThat(r.getStatusCode()).as(path + " body=" + r.getBody()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
            assertThat(r.getStatusCode().value()).as(path).isLessThan(500);
        }
        ResponseEntity<String> post = exchange("/v1/booking-requests/" + UUID.randomUUID() + "/decline", HttpMethod.POST,
                authedJsonBody(bad, Map.of("reasonCode", "other", "note", "x")), String.class);
        assertThat(post.getStatusCode().value()).as("POST " + post.getBody()).isIn(401, 403);
    }

    @Test
    void p02_reSignedTokenWithTenantStillWorks_controlForP01() throws Exception {
        Caller c = caller("control");
        Map<String, Object> cl = claims(c.token());
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(600))
                .subject((String) cl.get("sub")).claim("tenantId", cl.get("tenantId"));
        for (String k : List.of("roleId", "sid", "permissions", "tenantName", "tenantRegion", "staffName", "roleName", "owner", "pinChange")) {
            if (cl.get(k) != null) b.claim(k, cl.get(k));
        }
        TokenType.ACCESS.stamp(b, Audience.STAFF_API);
        String good = encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(good), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void p03_bookingNoteBoundary_280IsAccepted_281Is400_onAcceptAndDecline() {
        Caller c = caller("note");
        UUID unknown = UUID.randomUUID();
        // 280 passes validation and reaches the service (unknown request -> 404); 281 is refused before (400 naming note)
        ResponseEntity<Map> d280 = exchange("/v1/booking-requests/" + unknown + "/decline", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reasonCode", "other", "note", "x".repeat(280))), Map.class);
        assertThat(d280.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> a281 = exchange("/v1/booking-requests/" + unknown + "/accept", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("slotStart", Instant.now().plusSeconds(86400).toString(), "note", "y".repeat(281))), Map.class);
        assertThat(a281.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(a281.getBody().get("type"))).endsWith("validation-failed");
        assertThat((List<?>) a281.getBody().get("errors")).anySatisfy(e -> assertThat(((Map<?, ?>) e).get("field")).isEqualTo("note"));
        ResponseEntity<Map> a280 = exchange("/v1/booking-requests/" + unknown + "/accept", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("slotStart", Instant.now().plusSeconds(86400).toString(), "note", "y".repeat(280))), Map.class);
        assertThat(a280.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void p04_malformedAndWrongTypeBodiesAre400Problems() {
        Caller c = caller("malformed");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> broken = exchange("/v1/booking-requests/" + UUID.randomUUID() + "/decline", HttpMethod.POST, new HttpEntity<>("{\"note\": ", h), Map.class);
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(broken.getHeaders().getContentType().toString()).contains("problem+json");
        ResponseEntity<Map> wrongType = exchange("/v1/booking-requests/" + UUID.randomUUID() + "/suggest", HttpMethod.POST, new HttpEntity<>("{\"slots\": \"tomorrow\"}", h), Map.class);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> plan = exchange("/v1/setup/subscription/change", HttpMethod.POST, authedJsonBody(c.token(), Map.of("targetPlanCode", "", "effective", "later")), Map.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void p05_authFlowsUnchanged_wrongPinAndUnknownUserGiveTheSameGeneric401() {
        Caller c = caller("auth");
        ResponseEntity<Map> wrong = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", c.t().slug(), "email", c.s().email(), "pin", "9876")), Map.class);
        ResponseEntity<Map> unknown = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", c.t().slug(), "email", "nobody-" + UUID.randomUUID() + "@x.test", "pin", "9876")), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("type")).isEqualTo(unknown.getBody().get("type")); // anti-enumeration unchanged
        assertThat(wrong.getBody().get("detail")).isEqualTo(unknown.getBody().get("detail"));
    }
}
