package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10 PLT-027): Problem+JSON everywhere, bounded lists, API security headers. */
class S10QaProblemPagingHeadersTest extends ApiTestBase {

    @Autowired
    private JwtEncoder jwtEncoder;

    private SeededStaff owner(SeededTenant t) {
        return seedStaff(t, seedFullAccessRole(t.id()), "p27o" + UUID.randomUUID().toString().substring(0, 6) + "@q.com", "+91940070" + String.format("%04d", (int) (Math.random() * 9000)), false);
    }

    private ResponseEntity<Map> get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    private String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    @SuppressWarnings("unchecked")
    private boolean namesField(ResponseEntity<Map> r, String field) {
        Object errs = r.getBody().get("errors");
        String detail = String.valueOf(r.getBody().get("detail"));
        boolean inErrors = errs instanceof List<?> l && l.stream().anyMatch(e -> e instanceof Map<?, ?> m && field.equals(m.get("field")));
        return inErrors || detail.contains(field);
    }

    // ---------------- AC1/AC2 limit bounds, every list endpoint that takes ?limit ----------------
    private List<String> staffListPaths() {
        UUID pid = UUID.randomUUID();
        return List.of("/v1/patients", "/v1/patients/registry", "/v1/staff", "/v1/appointments", "/v1/appointments/follow-ups",
                "/v1/clinical/encounters", "/v1/clinical/patients/" + pid + "/timeline");
    }

    @Test
    void ac1_limitZeroNegativeHugeNonNumeric_is400ProblemNamingLimit_onEveryListEndpoint() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        List<String> failures = new ArrayList<>();
        for (String path : staffListPaths()) {
            for (String bad : List.of("0", "-5", path.endsWith("/registry") ? "201" : "101", "1000000", "abc", "2147483648")) { // OPS-008: registry max is 200
                ResponseEntity<Map> r = get(tok, path + "?limit=" + bad);
                boolean ok = r.getStatusCode() == HttpStatus.BAD_REQUEST && r.getBody() != null && type(r).contains("validation") && namesField(r, "limit");
                if (!ok) failures.add(path + "?limit=" + bad + " -> " + r.getStatusCode() + " " + r.getBody());
            }
        }
        assertThat(failures).as("every bad limit is a 400 validation problem naming 'limit'").isEmpty();
    }

    @Test
    void ac1_limitBoundaries_1And100_areAccepted_onEveryListEndpoint() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        List<String> failures = new ArrayList<>();
        for (String path : staffListPaths()) {
            for (String ok : List.of("1", "100")) {
                ResponseEntity<Map> r = get(tok, path + "?limit=" + ok);
                if (r.getStatusCode().is5xxServerError() || r.getStatusCode() == HttpStatus.BAD_REQUEST) failures.add(path + "?limit=" + ok + " -> " + r.getStatusCode() + " " + r.getBody());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void ac1_platformListEndpoints_alsoRejectBadLimits() {
        String tok = platformLoginAndGetAccessToken(seedOperator("p27op" + UUID.randomUUID().toString().substring(0, 5) + "@nabd.internal", "super_admin", false));
        List<String> failures = new ArrayList<>();
        for (String path : List.of("/v1/platform/audit-log", "/v1/platform/billing/subscriptions", "/v1/platform/billing/discounts", "/v1/platform/tenants")) {
            for (String bad : List.of("0", "-5", "101", "1000000")) {
                ResponseEntity<Map> r = get(tok, path + "?limit=" + bad);
                if (r.getStatusCode() != HttpStatus.BAD_REQUEST || !type(r).contains("validation")) failures.add(path + "?limit=" + bad + " -> " + r.getStatusCode() + " " + r.getBody());
            }
            ResponseEntity<Map> good = get(tok, path + "?limit=100");
            if (good.getStatusCode() != HttpStatus.OK) failures.add(path + "?limit=100 -> " + good.getStatusCode());
        }
        assertThat(failures).isEmpty();
    }

    // ---------------- AC2 default 25 + nextCursor ----------------
    @Test
    @SuppressWarnings("unchecked")
    void ac2_withoutLimit_atMost25Rows_andNextCursor_thenCursorWalkCoversEveryRowOnce() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        for (int i = 0; i < 30; i++) {
            ResponseEntity<Map> c = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "Qzx" + (char) ('a' + i % 26) + (char) ('k' + i / 26) + " Vwy" + (char) ('b' + (i * 7) % 26), "phone", "+9198888" + String.format("%05d", i), "dob", (1950 + i) + "-0" + (1 + i % 9) + "-1" + (i % 9), "gender", i % 2 == 0 ? "male" : "female")), Map.class);
            assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<Map> first = get(tok, "/v1/patients");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> d1 = (List<Map<String, Object>>) first.getBody().get("data");
        Map<String, Object> page = (Map<String, Object>) first.getBody().get("page");
        assertThat(d1).hasSize(25);
        assertThat(page.get("nextCursor")).isNotNull();
        assertThat(page.get("limit")).isEqualTo(25);

        Set<Object> seen = new HashSet<>();
        d1.forEach(p -> seen.add(p.get("id")));
        ResponseEntity<Map> second = get(tok, "/v1/patients?cursor=" + page.get("nextCursor"));
        List<Map<String, Object>> d2 = (List<Map<String, Object>>) second.getBody().get("data");
        assertThat(d2).hasSize(5);
        d2.forEach(p -> assertThat(seen.add(p.get("id"))).as("no duplicate across pages").isTrue());
        assertThat(((Map<String, Object>) second.getBody().get("page")).get("nextCursor")).isNull();

        // limit=100 returns all 30 in one page with no cursor; limit=1 pages one at a time
        ResponseEntity<Map> all = get(tok, "/v1/patients?limit=100");
        assertThat((List<?>) all.getBody().get("data")).hasSize(30);
        assertThat(((Map<String, Object>) all.getBody().get("page")).get("nextCursor")).isNull();
        ResponseEntity<Map> one = get(tok, "/v1/patients?limit=1");
        assertThat((List<?>) one.getBody().get("data")).hasSize(1);
        assertThat(((Map<String, Object>) one.getBody().get("page")).get("nextCursor")).isNotNull();
        // a tampered cursor is a 4xx problem, never a 500
        ResponseEntity<Map> bad = get(tok, "/v1/patients?cursor=%25%25not-a-cursor");
        assertThat(bad.getStatusCode().is4xxClientError()).as("tampered cursor -> " + bad.getStatusCode() + " " + bad.getBody()).isTrue();
    }

    @Test
    void ac2_listsOfOtherTenantAreNeverMixedIn() {
        SeededTenant a = seedTenant(), b = seedTenant();
        String ta = loginAndGetAccessToken(owner(a)), tb = loginAndGetAccessToken(owner(b));
        exchange("/v1/patients", HttpMethod.POST, authedJsonBody(ta, Map.of("name", "Only In A", "phone", "+919777700001", "dob", "1990-01-01", "gender", "male")), Map.class);
        ResponseEntity<Map> r = get(tb, "/v1/patients?limit=100");
        assertThat((List<?>) r.getBody().get("data")).isEmpty();
    }

    // Story title: "every list is bounded". 59 list endpoints were unbounded; sample an unparameterised one with > 100 rows.
    @Test
    void title_everyListIsBounded_rolesListWith120Rows_isCappedAt100() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        for (int i = 0; i < 120; i++) seedRole(t.id(), "Bulk" + i, false, fullGrant("patients"));
        ResponseEntity<List> r = exchange("/v1/roles", HttpMethod.GET, authed(tok), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().size()).as("GET /v1/roles returned %d rows", r.getBody().size()).isLessThanOrEqualTo(100);
    }

    // ---------------- AC3 token missing an expected claim ----------------
    private String token(Map<String, Object> claims, String... omit) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(600));
        Map<String, Object> all = new java.util.HashMap<>(claims);
        for (String o : omit) all.remove(o);
        all.forEach((k, v) -> {
            if ("sub".equals(k)) b.subject((String) v); else b.claim(k, v);
        });
        com.nabd.hms.shared.security.TokenType.ACCESS.stamp(b, com.nabd.hms.shared.security.Audience.STAFF_API);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    @Test
    void ac3_tokenMissingTenantIdSubOrSid_is401Problem_neverA500_onSeveralEndpoints() {
        SeededTenant t = seedTenant();
        SeededStaff s = owner(t);
        Map<String, Object> full = Map.of("sub", s.id().toString(), "tenantId", t.id().toString(), "sid", UUID.randomUUID().toString(),
                "permissions", List.of("patients:view", "staff:view", "queue:view", "clinical:view", "staff:edit"));
        List<String> failures = new ArrayList<>();
        for (String omit : List.of("tenantId")) {
            String tok = token(full, omit);
            for (String path : List.of("/v1/patients", "/v1/staff", "/v1/appointments", "/v1/patients/registry", "/v1/clinical/encounters")) {
                ResponseEntity<Map> r = get(tok, path);
                boolean ok = r.getStatusCode() == HttpStatus.UNAUTHORIZED && r.getBody() != null && r.getBody().get("type") != null;
                if (!ok) failures.add("omit " + omit + " " + path + " -> " + r.getStatusCode() + " " + r.getBody());
            }
        }
        String noSid = token(full, "sid");
        for (String path : List.of("/v1/auth/sessions")) {
            ResponseEntity<String> r = exchange(path, HttpMethod.GET, authed(noSid), String.class);
            if (r.getStatusCode().is5xxServerError()) failures.add("omit sid " + path + " -> " + r.getStatusCode());
        }
        ResponseEntity<Map> logout = exchange("/v1/auth/logout", HttpMethod.POST, authed(noSid), Map.class);
        if (logout.getStatusCode().is5xxServerError()) failures.add("omit sid logout -> " + logout.getStatusCode());
        assertThat(failures).isEmpty();
    }

    /** Title: "every error is clean Problem+JSON". Spring Security's own 401/403 are written before MVC, so check them explicitly. */
    @Test
    void title_securityLayer401And403_areProblemJson_notEmptyBodies() {
        SeededTenant t = seedTenant();
        SeededStaff s = owner(t);
        UUID ro = seedRole(t.id(), "RO2", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        String low = loginAndGetAccessToken(seedStaff(t, ro, "p27low2@q.com", "+919400700098", false));
        String noSub = token(Map.of("tenantId", t.id().toString(), "sid", UUID.randomUUID().toString(), "permissions", List.of("patients:view")), "sub");
        List<String> failures = new ArrayList<>();
        Map<String, HttpEntity<Void>> cases = new java.util.LinkedHashMap<>();
        cases.put("401 no token", new HttpEntity<>(new HttpHeaders()));
        HttpHeaders g = new HttpHeaders();
        g.setBearerAuth("garbage");
        cases.put("401 garbage token", new HttpEntity<>(g));
        cases.put("401 token without sub", authed(noSub));
        cases.put("403 @PreAuthorize denied", authed(low));
        for (var e : cases.entrySet()) {
            ResponseEntity<String> r = http.exchange(url("/v1/patients"), HttpMethod.GET, e.getValue(), String.class);
            String ct = String.valueOf(r.getHeaders().getContentType());
            boolean problem = ct.contains("problem+json") && r.getBody() != null && r.getBody().contains("\"type\"");
            if (!problem) failures.add(e.getKey() + " -> " + r.getStatusCode() + " content-type=" + ct + " body=" + r.getBody());
        }
        assertThat(failures).as("security-layer errors without a Problem+JSON body").isEmpty();
    }

    @Test
    void ac3_tokenWithoutPermissionsClaim_isNever500() {
        SeededTenant t = seedTenant();
        SeededStaff s = owner(t);
        String tok = token(Map.of("sub", s.id().toString(), "tenantId", t.id().toString(), "sid", UUID.randomUUID().toString()), "permissions");
        ResponseEntity<Map> r = get(tok, "/v1/patients");
        assertThat(r.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void ac3_malformedAndGarbageBearerTokens_are401Problem() {
        for (String bad : List.of("garbage", "a.b.c", "")) {
            HttpHeaders h = new HttpHeaders();
            h.set("Authorization", "Bearer " + bad);
            ResponseEntity<String> r = http.exchange(url("/v1/patients"), HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertThat(r.getStatusCode()).as("token '%s'", bad).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---------------- AC4 unhandled exception -> 500 Problem with traceId, no leakage ----------------
    @Test
    void ac4_unhandledExceptions_are500Problem_withTraceId_noStackNoSqlNoInternals() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        for (String kind : List.of("sql", "npe", "checked")) {
            ResponseEntity<String> r = exchange("/v1/s10qa/boom/" + kind, HttpMethod.GET, authed(tok), String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(r.getHeaders().getContentType().toString()).contains("problem+json");
            String body = r.getBody();
            assertThat(body).contains("\"traceId\"").contains("\"type\"").contains("\"status\":500");
            assertThat(body).doesNotContain("SELECT", "patients", "S1234567D", "secret", "NullPointer", "IllegalState", "java.", "at com.nabd", "stackTrace", ".java", "relation");
        }
    }

    @Test
    void ac4_unknownRoute_and_wrongMethod_and_badJson_and_wrongMediaType_areProblemJson_neverHtmlNorWhitelabel() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        ResponseEntity<Map> nf = get(tok, "/v1/does-not-exist");
        assertThat(nf.getStatusCode().value()).isIn(404);
        assertThat(nf.getBody()).containsKey("type");
        ResponseEntity<Map> wm = exchange("/v1/patients", HttpMethod.DELETE, authed(tok), Map.class);
        assertThat(wm.getStatusCode().value()).isIn(405);
        assertThat(wm.getBody()).containsKey("type");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tok);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<Map> bad = exchange("/v1/patients", HttpMethod.POST, new HttpEntity<>("{not json", h), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsKey("type").containsKey("traceId");
        h.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        ResponseEntity<Map> mt = exchange("/v1/patients", HttpMethod.POST, new HttpEntity<>("hello", h), Map.class);
        assertThat(mt.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(mt.getBody()).containsKey("type");
        ResponseEntity<Map> badUuid = get(tok, "/v1/staff/not-a-uuid");
        assertThat(badUuid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nfr_noPhiInValidationErrorDetail_rejectedValuesAreNotEchoed() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        ResponseEntity<String> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "PHI-MARKER-ZXQ Name", "phone", "PHI-MARKER-ZXQ-phone", "dob", "PHI-MARKER-ZXQ", "gender", "PHI-MARKER-ZXQ")), String.class);
        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        assertThat(r.getBody()).doesNotContain("PHI-MARKER-ZXQ");
    }

    // ---------------- AC5 security headers on 200 / 4xx / 5xx / 401 / 404 ----------------
    private void assertHeaders(ResponseEntity<?> r, String what) {
        HttpHeaders h = r.getHeaders();
        assertThat(h.getCacheControl()).as(what + " Cache-Control").contains("no-store");
        assertThat(h.getFirst("X-Content-Type-Options")).as(what + " nosniff").isEqualTo("nosniff");
        assertThat(h.getFirst("Referrer-Policy")).as(what + " Referrer-Policy").isEqualTo("no-referrer");
        String hsts = h.getFirst("Strict-Transport-Security");
        assertThat(hsts).as(what + " HSTS").isNotNull();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max-age=(\\d+)").matcher(hsts);
        assertThat(m.find()).isTrue();
        assertThat(Long.parseLong(m.group(1))).as(what + " HSTS max-age").isGreaterThanOrEqualTo(31536000L);
        String csp = h.getFirst("Content-Security-Policy");
        assertThat(csp).as(what + " CSP").contains("default-src 'none'").contains("frame-ancestors 'none'");
    }

    @Test
    void ac5_securityHeaders_on200_400_401_403_404_405_and500() {
        SeededTenant t = seedTenant();
        SeededStaff o = owner(t);
        String tok = loginAndGetAccessToken(o);
        UUID ro = seedRole(t.id(), "RO", false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff low = seedStaff(t, ro, "p27low@q.com", "+919400700099", false);
        assertHeaders(get(tok, "/v1/patients"), "200");
        assertHeaders(get(tok, "/v1/patients?limit=0"), "400");
        assertHeaders(exchange("/v1/patients", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class), "401 no token");
        assertHeaders(get(loginAndGetAccessToken(low), "/v1/patients"), "403");
        assertHeaders(get(tok, "/v1/does-not-exist"), "404");
        assertHeaders(exchange("/v1/patients", HttpMethod.DELETE, authed(tok), Map.class), "405");
        assertHeaders(exchange("/v1/s10qa/boom/sql", HttpMethod.GET, authed(tok), String.class), "500");
        assertHeaders(http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", t.slug(), "email", o.email(), "pin", "9999")), Map.class), "public login 401");
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer garbage");
        assertHeaders(http.exchange(url("/v1/patients"), HttpMethod.GET, new HttpEntity<>(h), String.class), "401 bad token");
    }

    @Test
    void ac6_proxyForSchemathesis_noServerErrorOnFuzzedListParams() {
        // Schemathesis is not installed in this sandbox; see report. The equivalent proxy (no 5xx on fuzzed limit/cursor/UUID inputs) is below.
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(owner(t));
        List<String> failures = new ArrayList<>();
        String[] fuzz = {"", "%00", "-1", "9999999999999999999999", "null", "'; DROP TABLE patients;--", "%E2%82%AC", "1e9", "0x10", " ", "true"};
        for (String path : staffListPaths()) {
            for (String f : fuzz) {
                for (String q : List.of("limit=" + f, "cursor=" + f, "q=" + f, "from=" + f, "state=" + f, "status=" + f, "filter=" + f)) {
                    ResponseEntity<String> r = exchange(path + "?" + q, HttpMethod.GET, authed(tok), String.class);
                    if (r.getStatusCode().is5xxServerError()) failures.add(path + "?" + q + " -> " + r.getStatusCode() + " " + r.getBody());
                }
            }
        }
        assertThat(failures).as("no server error on fuzzed list parameters").isEmpty();
    }
}
