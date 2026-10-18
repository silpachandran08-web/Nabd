package com.nabd.hms.auth;

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
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA for PLT-001 (typed tokens). Own enumeration, own token minting, own expectations. */
class TokenTypesS9QaTest extends ApiTestBase {

    private static final String ISS = "https://api.nabd.health";

    @Autowired JwtEncoder qaEncoder;
    @Autowired RequestMappingHandlerMapping qaMappings;

    // ---- helpers -------------------------------------------------------------------------------
    private String mint(String typ, String aud, UUID sub, UUID tenant, UUID sid, List<String> perms, long ttlSec, String iss, String purpose) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(iss).issuedAt(ttlSec < 0 ? now.plusSeconds(ttlSec - 60) : now.minusSeconds(5)).expiresAt(now.plusSeconds(ttlSec))
                .subject(sub.toString());
        if (tenant != null) b.claim("tenantId", tenant.toString());
        if (sid != null) b.claim("sid", sid.toString());
        if (perms != null) b.claim("permissions", perms);
        if (typ != null) b.claim("typ", typ);
        if (aud != null) b.audience(List.of(aud));
        if (purpose != null) b.claim("purpose", purpose);
        return qaEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String jwt) {
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpStatus callStatus(String method, String path, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        ResponseEntity<String> r = http.exchange(url(path), HttpMethod.valueOf(method), new HttpEntity<>("{}", h), String.class);
        return HttpStatus.valueOf(r.getStatusCode().value());
    }

    private SeededStaff mfaStaff(String email, String mobile) {
        SeededTenant t = seedTenant();
        return seedStaff(t, seedFullAccessRole(t.id()), email, mobile, true);
    }

    private SeededStaff plainStaff(String email, String mobile) {
        SeededTenant t = seedTenant();
        return seedStaff(t, seedFullAccessRole(t.id()), email, mobile, false);
    }

    @SuppressWarnings("unchecked")
    private String realMfaPending(SeededStaff s) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("challengeId");
    }

    /** every (method, concrete path) the app maps, discovered from the live handler mapping */
    private List<String[]> endpoints() {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : qaMappings.getHandlerMethods().entrySet()) {
            var pp = e.getKey().getPathPatternsCondition();
            if (pp == null) continue;
            var methods = e.getKey().getMethodsCondition().getMethods();
            String m = methods.isEmpty() ? "GET" : methods.iterator().next().name();
            for (var p : pp.getPatterns()) {
                String path = p.getPatternString().replaceAll("\\{[^}/]+\\}", UUID.randomUUID().toString());
                out.add(new String[]{m, path});
            }
        }
        return out;
    }

    // ---- AC1: every token type x every mapping => 401 -------------------------------------------
    @Test
    void ac1_noNonAccessTokenIsAcceptedAsBearerOnAnyMapping() {
        SeededStaff mfa = mfaStaff("qa-matrix@a.com", "+919400000001");
        UUID sub = UUID.randomUUID(), ten = UUID.randomUUID(), sid = UUID.randomUUID();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("real mfa_pending (after correct PIN)", realMfaPending(mfa));
        tokens.put("mfa_pending/mfa", mint("mfa_pending", "mfa", sub, ten, null, null, 300, ISS, "mfa_pending"));
        tokens.put("mfa_pending/platform-mfa", mint("mfa_pending", "platform-mfa", sub, ten, null, null, 300, ISS, "platform_mfa_pending"));
        tokens.put("mfa_setup/mfa", mint("mfa_setup", "mfa", sub, ten, null, null, 300, ISS, "mfa_setup_required"));
        tokens.put("step_up/step-up", mint("step_up", "step-up", sub, ten, sid, List.of("staff:view"), 300, ISS, "step_up"));
        tokens.put("owner_select/owner-select", mint("owner_select", "owner-select", sub, null, null, null, 300, ISS, "owner_pending_workspace"));
        tokens.put("legacy (no typ, aud default)", mint(null, null, sub, ten, sid, List.of("staff:view"), 300, ISS, null));
        tokens.put("legacy no typ with aud staff-api", mint(null, "staff-api", sub, ten, sid, List.of("staff:view"), 300, ISS, null));
        tokens.put("typ=access but aud=mfa", mint("access", "mfa", sub, ten, sid, List.of("staff:view"), 300, ISS, null));
        tokens.put("typ=access but aud=step-up", mint("access", "step-up", sub, ten, sid, List.of("staff:view"), 300, ISS, null));
        tokens.put("typ=ACCESS (case)", mint("ACCESS", "staff-api", sub, ten, sid, List.of("staff:view"), 300, ISS, null));
        tokens.put("typ=access wrong issuer", mint("access", "staff-api", sub, ten, sid, List.of("staff:view"), 300, "https://evil.example", null));
        tokens.put("typ=access expired", mint("access", "staff-api", sub, ten, sid, List.of("staff:view"), -60, ISS, null));

        List<String[]> eps = endpoints();
        assertThat(eps.size()).as("endpoint enumeration found mappings").isGreaterThan(50);
        TreeSet<String> bad = new TreeSet<>();
        int calls = 0;
        for (var t : tokens.entrySet()) {
            for (String[] ep : eps) {
                boolean ownerConsumer = t.getKey().startsWith("owner_select")
                        && (ep[1].equals("/v1/owners/me/workspaces") || ep[1].equals("/v1/owners/workspaces/select"));
                if (ownerConsumer) continue; // documented consumer of an owner_select token
                HttpStatus s = callStatus(ep[0], ep[1], t.getValue());
                calls++;
                if (s != HttpStatus.UNAUTHORIZED) bad.add(t.getKey() + " -> " + ep[0] + " " + ep[1].replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "{id}") + " = " + s);
            }
        }
        System.out.println("QA-MATRIX endpoints=" + eps.size() + " tokenKinds=" + tokens.size() + " calls=" + calls + " nonOK=" + bad.size());
        assertThat(bad).as("requests that were NOT rejected with 401").isEmpty();
    }

    // ---- AC2: verify exchanges challenge for typed pair; fresh token works; claims -------------
    @Test
    @SuppressWarnings("unchecked")
    void ac2_mfaVerifyReturnsTypedAccessPairAndTokenOpensApi() {
        SeededStaff s = mfaStaff("qa-verify@a.com", "+919400000002");
        String challenge = realMfaPending(s);
        Map<String, Object> cc = claims(challenge);
        assertThat(cc.get("typ")).isEqualTo("mfa_pending");
        assertThat(cc.get("aud")).isIn("mfa", List.of("mfa"));
        assertThat(cc).doesNotContainKey("sid");

        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"),
                jsonBody(Map.of("challengeId", challenge, "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = (String) v.getBody().get("accessToken");
        Map<String, Object> ac = claims(access);
        assertThat(ac.get("typ")).isEqualTo("access");
        assertThat(String.valueOf(ac.get("aud"))).contains("staff-api");
        assertThat(ac.get("sid")).isNotNull();
        assertThat(v.getBody().get("refreshToken")).isNotNull();
        assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(access), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the challenge cannot be exchanged with a wrong code, a garbage token or an access token in its place
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", access, "code", "000000")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", "x.y.z", "code", "000000")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // a step_up token is not a challenge either
        String stepUp = mint("step_up", "step-up", s.id(), s.tenantId(), null, null, 300, ISS, "step_up");
        assertThat(http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", stepUp, "code", "000000")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac2b_mintSitesStampTypeAndAudience() {
        SeededStaff plain = plainStaff("qa-mint@a.com", "+919400000003");
        // staff access
        Map<String, Object> a = claims(loginAndGetAccessToken(plain));
        assertThat(a.get("typ")).isEqualTo("access");
        assertThat(String.valueOf(a.get("aud"))).contains("staff-api");
        assertThat(a.get("sid")).isNotNull();
        // mfa_setup via role flag
        SeededStaff need = plainStaff("qa-setup@a.com", "+919400000004");
        new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).update("UPDATE roles SET mfa_required = true WHERE id = ?", need.roleId());
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", need.slug(), "email", need.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setup = (String) r.getBody().values().stream().filter(v -> v instanceof String s && s.split("\\.").length == 3).findFirst().orElseThrow();
        Map<String, Object> sc = claims(setup);
        assertThat(sc.get("typ")).isEqualTo("mfa_setup");
        assertThat(String.valueOf(sc.get("aud"))).contains("mfa");
        // the setup token is a body credential only: bearer => 401, body => accepted
        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, authedJsonBody(setup, Map.of()), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, jsonBody(Map.of("setupToken", setup)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // an access token in the setupToken body slot is refused
        assertThat(exchange("/v1/auth/mfa/enroll", HttpMethod.POST, jsonBody(Map.of("setupToken", loginAndGetAccessToken(plain))), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // owner pending
        SeededOwner o = seedOwner("qa-owner@a.com");
        Map<String, Object> oc = claims(ownerLoginPendingToken(o));
        assertThat(oc.get("typ")).isEqualTo("owner_select");
        assertThat(String.valueOf(oc.get("aud"))).contains("owner-select");
        // operator access
        Map<String, Object> pc = claims(platformLoginAndGetAccessToken(seedOperator("qa-op@nabd.internal", "billing", false)));
        assertThat(pc.get("typ")).isEqualTo("access");
        assertThat(String.valueOf(pc.get("aud"))).contains("platform-api");
        assertThat(pc.get("sid")).isNotNull();
        // operator mfa_pending
        SeededOperator mop = seedOperator("qa-opm@nabd.internal", "billing", true);
        ResponseEntity<Map> pl = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", mop.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(pl.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pm = (String) pl.getBody().values().stream().filter(v -> v instanceof String s && s.split("\\.").length == 3).findFirst().orElseThrow();
        Map<String, Object> pmc = claims(pm);
        assertThat(pmc.get("typ")).isEqualTo("mfa_pending");
        assertThat(String.valueOf(pmc.get("aud"))).contains("platform-mfa");
        assertThat(callStatus("GET", "/v1/platform/plans", pm)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- AC3: step_up / owner_select ------------------------------------------------------------
    @Test
    void ac3_stepUpAndOwnerSelectAreNotBearers_stepUpHeaderChecksSubject() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff actor = seedStaff(t, role, "qa-su-actor@a.com", "+919400000005", true);
        SeededStaff other = seedStaff(t, role, "qa-su-other@a.com", "+919400000006", true);
        String access = loginAfterMfa(actor);

        // real step-up via /mfa/verify with Authorization
        ResponseEntity<Map> su = exchange("/v1/auth/mfa/verify", HttpMethod.POST, authedJsonBody(access, Map.of("challengeId", "unused", "code", currentTotpCode())), Map.class);
        assertThat(su.getStatusCode()).isEqualTo(HttpStatus.OK);
        String stepUp = (String) su.getBody().values().stream().filter(v -> v instanceof String s && s.split("\\.").length == 3).findFirst().orElseThrow();
        assertThat(claims(stepUp).get("typ")).isEqualTo("step_up");
        // as bearer: 401
        assertThat(callStatus("GET", "/v1/auth/sessions", stepUp)).isEqualTo(HttpStatus.UNAUTHORIZED);
        // owner_select as bearer: 401 on a normal endpoint
        String os = mint("owner_select", "owner-select", UUID.randomUUID(), null, null, null, 300, ISS, "owner_pending_workspace");
        assertThat(callStatus("GET", "/v1/auth/sessions", os)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callStatus("GET", "/v1/staff", os)).isEqualTo(HttpStatus.UNAUTHORIZED);

        // header with a step-up of ANOTHER subject is refused, with mine is accepted
        String foreignStepUp = mint("step_up", "step-up", other.id(), t.id(), null, null, 300, ISS, "step_up");
        SeededStaff victim1 = seedStaff(t, role, "qa-su-v1@a.com", "+919400000007", false);
        SeededStaff victim2 = seedStaff(t, role, "qa-su-v2@a.com", "+919400000008", false);
        SeededStaff victim3 = seedStaff(t, role, "qa-su-v3@a.com", "+919400000009", false);
        assertThat(suspend(access, victim1.id(), foreignStepUp)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend(access, victim1.id(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        // a mfa_pending / access token in the step-up header is refused
        assertThat(suspend(access, victim2.id(), realMfaPendingFor(other))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend(access, victim2.id(), access)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspend(access, victim3.id(), stepUp)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private HttpStatus suspend(String access, UUID victim, String stepUp) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        if (stepUp != null) h.set("X-Step-Up-Token", stepUp);
        return HttpStatus.valueOf(http.exchange(url("/v1/staff/" + victim + "/suspend"), HttpMethod.POST, new HttpEntity<>(h), String.class).getStatusCode().value());
    }

    private String realMfaPendingFor(SeededStaff s) { return realMfaPending(s); }

    @SuppressWarnings("unchecked")
    private String loginAfterMfa(SeededStaff s) {
        String ch = realMfaPending(s);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", ch, "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    // ---- AC4: legacy token -> 401, refresh still works -----------------------------------------
    @Test
    @SuppressWarnings("unchecked")
    void ac4_legacyTokenWithoutTypIs401_refreshGivesTypedToken() {
        SeededStaff s = plainStaff("qa-legacy@a.com", "+919400000010");
        ResponseEntity<Map> l = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        String access = (String) l.getBody().get("accessToken");
        String refresh = (String) l.getBody().get("refreshToken");
        Map<String, Object> c = claims(access);
        String legacy = mint(null, "staff-api", s.id(), s.tenantId(), UUID.fromString((String) c.get("sid")), List.of("staff:view"), 300, ISS, null);
        assertThat(callStatus("GET", "/v1/auth/sessions", legacy)).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> rr = http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", refresh)), Map.class);
        assertThat(rr.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> nc = claims((String) rr.getBody().get("accessToken"));
        assertThat(nc.get("typ")).isEqualTo("access");
        assertThat(nc.get("sid")).isNotNull();
        assertThat(callStatus("GET", "/v1/auth/sessions", (String) rr.getBody().get("accessToken"))).isEqualTo(HttpStatus.OK);
    }

    // ---- AC5: no @PreAuthorize endpoints need sid or authority ----------------------------------
    @Test
    void ac5_accessTokenWithoutSidIs401_onUnannotatedEndpoints() {
        UUID sub = UUID.randomUUID(), ten = UUID.randomUUID();
        String noSidNoPerms = mint("access", "staff-api", sub, ten, null, null, 300, ISS, null);
        String noSidEmptyPerms = mint("access", "staff-api", sub, ten, null, List.of(), 300, ISS, null);
        for (String tok : List.of(noSidNoPerms, noSidEmptyPerms)) {
            assertThat(callStatus("POST", "/v1/auth/logout", tok)).as("logout").isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(callStatus("GET", "/v1/auth/sessions", tok)).as("sessions").isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(callStatus("GET", "/v1/owners/me/workspaces", tok)).as("owners/me/workspaces").isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /** probe: sid missing but one authority present (allowed by the AuthorizationManager) must not blow up as a 500 */
    @Test
    void ac5b_probe_noSidButAuthority_neverA500() {
        String tok = mint("access", "staff-api", UUID.randomUUID(), UUID.randomUUID(), null, List.of("staff:view"), 300, ISS, null);
        for (String[] ep : new String[][]{{"POST", "/v1/auth/logout"}, {"GET", "/v1/auth/sessions"}, {"GET", "/v1/break-glass-none"}}) {
            HttpStatus s = callStatus(ep[0], ep[1], tok);
            System.out.println("QA-PROBE no-sid+authority " + ep[0] + " " + ep[1] + " = " + s);
            assertThat(s.is5xxServerError()).as(ep[1]).isFalse();
        }
    }

    // ---- audience separation staff vs platform ----------------------------------------------------
    @Test
    void staffAndOperatorAccessTokensDoNotCrossPathFamilies() {
        String staff = loginAndGetAccessToken(plainStaff("qa-cross@a.com", "+919400000011"));
        String op = platformLoginAndGetAccessToken(seedOperator("qa-cross-op@nabd.internal", "billing", false));
        assertThat(callStatus("GET", "/v1/platform/plans", staff)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callStatus("GET", "/v1/auth/sessions", op)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callStatus("GET", "/v1/platform/plans", op)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tamperedSignatureAndAlgNoneAreRejected() {
        String good = loginAndGetAccessToken(plainStaff("qa-tamper@a.com", "+919400000012"));
        String[] p = good.split("\\.");
        String tampered = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(p[1])).replace("staff-api", "staff-api").replace("\"access\"", "\"access\" ").getBytes()) + "." + p[2];
        assertThat(callStatus("GET", "/v1/auth/sessions", tampered)).isEqualTo(HttpStatus.UNAUTHORIZED);
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes()) + "." + p[1] + ".";
        assertThat(callStatus("GET", "/v1/auth/sessions", none)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(callStatus("GET", "/v1/auth/sessions", good)).isEqualTo(HttpStatus.OK);
    }
}
