package com.nabd.hms.auth;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-001 (MF-01): challenge / purpose tokens are useless as bearer credentials. The endpoint list
 * comes from RequestMappingHandlerMapping, so a new controller method is covered automatically.
 */
class TokenMisuseMatrixTest extends ApiTestBase {

    private static final String ISSUER = "https://api.nabd.health";
    /** The documented consumers: these endpoints read their own challenge token (owner_select) instead of the resource server. */
    private static final List<String> OWNER_SELECT_CONSUMERS = List.of("/v1/owners/me/workspaces", "/v1/owners/workspaces/select");

    @Autowired
    JwtEncoder encoder;
    @Autowired
    RequestMappingHandlerMapping mappings;

    private String mint(TokenType typ, String aud, boolean withSid, boolean withTypAud, UUID sub) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(ISSUER).issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(sub.toString()).claim("tenantId", UUID.randomUUID().toString()).claim("permissions", List.of("staff:view"));
        if (withSid) {
            b.claim("sid", UUID.randomUUID().toString());
        }
        if (withTypAud) {
            b.claim("typ", typ.claim()).audience(List.of(aud));
        } else {
            b.claim("purpose", typ.claim());
        }
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    /** token label -> token. None may open any endpoint. */
    private Map<String, String> misusedTokens() {
        UUID sub = UUID.randomUUID();
        Map<String, String> t = new LinkedHashMap<>();
        t.put("mfa_pending", mint(TokenType.MFA_PENDING, "mfa", false, true, sub));
        t.put("mfa_pending+sid", mint(TokenType.MFA_PENDING, "mfa", true, true, sub)); // even if it carried a sid
        t.put("platform_mfa_pending", mint(TokenType.MFA_PENDING, "platform-mfa", false, true, sub));
        t.put("mfa_setup", mint(TokenType.MFA_SETUP, "mfa", false, true, sub));
        t.put("step_up", mint(TokenType.STEP_UP, "step-up", false, true, sub));
        t.put("owner_select", mint(TokenType.OWNER_SELECT, "owner-select", false, true, sub));
        t.put("legacy-no-typ", mint(TokenType.ACCESS, "x", true, false, sub));
        t.put("access-without-sid", mint(TokenType.ACCESS, "staff-api", false, true, sub));
        t.put("access-wrong-aud", mint(TokenType.ACCESS, "mfa", true, true, sub));
        return t;
    }

    private record Endpoint(HttpMethod method, String path) {
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().isAnnotationPresent(RestController.class)
                    || !e.getValue().getBeanType().getName().startsWith("com.nabd.hms.")) {
                continue;
            }
            var methods = e.getKey().getMethodsCondition().getMethods();
            List<String> verbs = methods.isEmpty() ? List.of("GET") : methods.stream().map(Enum::name).toList();
            for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/v1/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
                verbs.forEach(v -> out.add(new Endpoint(HttpMethod.valueOf(v), path)));
            }
        }
        return out;
    }

    @Test
    void everyMisusedTokenIsRefusedOnEveryRequestMapping() {
        List<Endpoint> endpoints = endpoints();
        assertThat(endpoints.size()).as("discovered request mappings").isGreaterThan(50);
        List<String> accepted = new ArrayList<>();
        for (var tok : misusedTokens().entrySet()) {
            for (Endpoint ep : endpoints) {
                boolean consumer = tok.getKey().equals("owner_select") && OWNER_SELECT_CONSUMERS.contains(ep.path());
                if (consumer) {
                    continue; // checked in ownerSelectOnlyWorksOnItsOwnTwoEndpoints
                }
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(tok.getValue());
                h.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<String> r = http.exchange(url(ep.path()), ep.method(), new HttpEntity<>("{\"clinicId\":\"" + UUID.randomUUID() + "\"}", h), String.class);
                if (r.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                    accepted.add(tok.getKey() + " " + ep.method() + " " + ep.path() + " -> " + r.getStatusCode());
                }
            }
        }
        assertThat(accepted).as("non-access token accepted").isEmpty();
    }

    @Test
    void mfaPendingFromARealPinLoginCannotEnrolUnlockOrBreakGlass() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "mf01@a.com", "+919000000201", true);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String challenge = (String) login.getBody().get("challengeId");
        assertThat(claims(challenge)).contains("\"typ\":\"mfa_pending\"").contains("\"aud\":\"mfa\"");

        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authedJsonBody(challenge, Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/unlock", HttpMethod.POST, authedJsonBody(challenge, Map.of("pin", STAFF_PIN)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBody(challenge, Map.of("reason", "need access now please")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // the same token inside the body of an enrol call is not a setup token either
        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, jsonBody(Map.of("setupToken", challenge)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, jsonBody(Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // and it still does its one job: with a valid TOTP it yields typ=access, aud=staff-api, sid
        ResponseEntity<Map> verify = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challenge, "code", currentTotpCode())), Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = (String) verify.getBody().get("accessToken");
        assertThat(claims(access)).contains("\"typ\":\"access\"").contains("\"aud\":\"staff-api\"").contains("\"sid\":\"");
    }

    @Test
    void stepUpTokenIsNoBearerAndOnlyCountsForItsOwnSubject() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "su@a.com", "+919000000202", true);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> verify = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", login.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        String access = (String) verify.getBody().get("accessToken");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(MediaType.APPLICATION_JSON);
        String stepUp = (String) http.postForEntity(url("/v1/auth/mfa/verify"),
                new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h), Map.class)
                .getBody().get("stepUpToken");
        assertThat(claims(stepUp)).contains("\"typ\":\"step_up\"").contains("\"aud\":\"step-up\"");
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(stepUp), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // StepUpVerifier: own subject accepted, someone else's step-up token and an access token refused
        var verifier = new com.nabd.hms.common.StepUpVerifier(decoder);
        verifier.require(stepUp, staff.id());
        String otherStepUp = mint(TokenType.STEP_UP, "step-up", false, true, UUID.randomUUID());
        org.junit.jupiter.api.Assertions.assertThrows(com.nabd.hms.common.ApiException.class, () -> verifier.require(otherStepUp, staff.id()));
        org.junit.jupiter.api.Assertions.assertThrows(com.nabd.hms.common.ApiException.class, () -> verifier.require(access, staff.id()));
    }

    @Autowired
    com.nabd.hms.shared.security.ChallengeTokenDecoder decoder;

    @Test
    void ownerSelectOnlyWorksOnItsOwnTwoEndpoints() {
        SeededOwner owner = seedOwner("own-sel@a.com");
        String pending = ownerLoginPendingToken(owner);
        assertThat(claims(pending)).contains("\"typ\":\"owner_select\"").contains("\"aud\":\"owner-select\"");
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(pending), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(pending), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // an access token is not an owner_select token
        SeededTenant tenant = seedTenant();
        String access = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "os@a.com", "+919000000203", false));
        assertThat(exchange("/v1/owners/me/workspaces", HttpMethod.GET, authed(access), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity(url("/v1/owners/me/workspaces"), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void legacyTokenWithoutTypIsRejectedButRefreshStillWorks() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "leg@a.com", "+919000000204", false);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String sid = (String) ((Map<?, ?>) parse(claims((String) login.getBody().get("accessToken")))).get("sid");
        Instant now = Instant.now();
        String legacy = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(ISSUER).issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject(staff.id().toString()).claim("tenantId", tenant.id().toString()).claim("sid", sid)
                .claim("permissions", List.of("staff:view")).build())).getTokenValue();
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(legacy), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> refreshed = http.postForEntity(url("/v1/auth/refresh"),
                jsonBody(Map.of("refreshToken", login.getBody().get("refreshToken"))), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String fresh = (String) refreshed.getBody().get("accessToken");
        assertThat(claims(fresh)).contains("\"typ\":\"access\"");
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(fresh), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void endpointsWithoutPreAuthorizeRequireASid() {
        String noSid = mint(TokenType.ACCESS, "staff-api", false, true, UUID.randomUUID());
        for (String path : List.of("/v1/auth/logout", "/v1/auth/sessions", "/v1/owners/me/workspaces")) {
            HttpMethod m = path.endsWith("logout") ? HttpMethod.POST : HttpMethod.GET;
            assertThat(exchange(path, m, authed(noSid), Map.class).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void platformMfaChallengeIsNoBearerAndPlatformAccessIsTyped() {
        SeededOperator op = seedOperator("tm-op@nabd.test", "super_admin", true);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", op.email(), "pin", STAFF_PIN)), Map.class);
        String ch = (String) login.getBody().get("challengeId");
        assertThat(claims(ch)).contains("\"typ\":\"mfa_pending\"").contains("\"aud\":\"platform-mfa\"");
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(ch), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(claims(platformLoginAndGetAccessToken(seedOperator("tm-op2@nabd.test", "super_admin", false))))
                .contains("\"typ\":\"access\"").contains("\"aud\":\"platform-api\"");
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
