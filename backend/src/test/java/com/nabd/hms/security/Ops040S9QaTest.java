package com.nabd.hms.security;

import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9 independent QA of OPS-040. Unlike the engineer's matrix (which mints tokens with the legacy "purpose" claim, i.e.
 * tokens the typed decoder would reject for being untyped), every challenge token here carries the REAL typ/aud claims
 * that PLT-001 stamps, plus a live session id and permissions, so only the type/audience check can refuse them.
 * Every pattern x every HTTP verb of every controller is probed (no first-verb shortcut).
 */
class Ops040S9QaTest extends ApiTestBase {

    private static final String ISSUER = "https://api.nabd.health";
    private static final Path OUT = Path.of(System.getProperty("s9qa.out", "target/s9qa-ops040.txt"));

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired JwtEncoder encoder;

    record Op(HttpMethod method, String path, String handler) {
        String label() { return method + " " + path; }
    }

    private static final Pattern PATH_VAR = Pattern.compile("\\{[^}/]+}");

    List<Op> allOps() {
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Class<?> bean = e.getValue().getBeanType();
            if (!bean.isAnnotationPresent(RestController.class) || !bean.getName().startsWith("com.nabd.hms.")) continue;
            var verbs = e.getKey().getMethodsCondition().getMethods();
            for (String p : e.getKey().getPathPatternsCondition().getPatternValues()) {
                if (!p.startsWith("/v1/")) continue;
                String path = PATH_VAR.matcher(p).replaceAll(UUID.randomUUID().toString());
                String h = bean.getSimpleName() + "." + e.getValue().getMethod().getName();
                if (verbs.isEmpty()) ops.add(new Op(HttpMethod.GET, path, h));
                else verbs.forEach(v -> ops.add(new Op(HttpMethod.valueOf(v.name()), path, h)));
            }
        }
        return ops;
    }

    int call(Op op, String bearer, Map<String, String> extraHeaders) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        extraHeaders.forEach(h::set);
        return http.exchange(url(op.path()), op.method(), new HttpEntity<>("{}", h), String.class).getStatusCode().value();
    }

    String mint(JwtEncoder enc, String iss, String typ, String aud, UUID sub, UUID tenant, String sid, List<String> perms, Instant exp) {
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(iss).issuedAt(exp.isBefore(Instant.now().minusSeconds(120)) ? exp.minusSeconds(900) : Instant.now().minusSeconds(120)).expiresAt(exp)
                .subject(sub.toString()).claim("tenantId", tenant.toString()).claim("permissions", perms);
        if (typ != null) b.claim("typ", typ);
        if (aud != null) b.audience(List.of(aud));
        if (sid != null) b.claim("sid", sid);
        return enc.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    static String claim(String jwt, String name) {
        String json = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void nineTokenTypesAreRefusedOnEveryProtectedOperationExceptTheirOwn() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID fullRole = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, fullRole, "q40@a.com", "+919000004001", false);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String access = (String) login.getBody().get("accessToken");
        String refresh = (String) login.getBody().get("refreshToken");
        String sid = claim(access, "sid");
        // the REAL permission set of a full-access role, so a wrongly accepted challenge token would carry every authority
        String permJson = new String(Base64.getUrlDecoder().decode(access.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        Matcher pm = Pattern.compile("\"permissions\":\\[([^\\]]*)]").matcher(permJson);
        assertThat(pm.find()).isTrue();
        List<String> perms = new ArrayList<>();
        Matcher one = Pattern.compile("\"([^\"]+)\"").matcher(pm.group(1));
        while (one.find()) perms.add(one.group(1));
        assertThat(perms).as("full-access role permissions").hasSizeGreaterThan(10);
        Instant ok = Instant.now().plusSeconds(300);
        UUID sub = staff.id();

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair foreign = g.generateKeyPair();
        JwtEncoder foreignEnc = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new RSAKey.Builder((RSAPublicKey) foreign.getPublic()).privateKey((RSAPrivateKey) foreign.getPrivate()).keyID("foreign").build())));

        Map<String, String> tokens = new LinkedHashMap<>();
        Map<String, Predicate<String>> own = new LinkedHashMap<>();
        // 1-3: challenge tokens, minted with typ/aud, a LIVE session id and full permissions
        tokens.put("mfa_pending", mint(encoder, ISSUER, "mfa_pending", "mfa", sub, tenant.id(), sid, perms, ok));
        tokens.put("mfa_pending/platform-mfa", mint(encoder, ISSUER, "mfa_pending", "platform-mfa", sub, tenant.id(), sid, perms, ok));
        tokens.put("mfa_setup_required", mint(encoder, ISSUER, "mfa_setup", "mfa", sub, tenant.id(), sid, perms, ok));
        own.put("mfa_setup_required", p -> p.equals("/v1/auth/mfa/enroll") || p.equals("/v1/auth/mfa/confirm"));
        tokens.put("step_up", mint(encoder, ISSUER, "step_up", "step-up", sub, tenant.id(), sid, perms, ok));
        // 4: owner pending (real, from a real owner login)
        tokens.put("owner_pending", ownerLoginPendingToken(seedOwner("q40own@a.com")));
        own.put("owner_pending", p -> p.equals("/v1/owners/me/workspaces") || p.equals("/v1/owners/workspaces/select"));
        // 5: platform operator (real)
        tokens.put("platform_operator", platformLoginAndGetAccessToken(seedOperator("q40op@nabd.test", "super_admin", false)));
        own.put("platform_operator", p -> p.startsWith("/v1/platform/"));
        // 6: refresh token presented as bearer (real, opaque)
        tokens.put("refresh_as_access", refresh);
        // 7: otherwise perfect access token, expired 10 minutes ago (the 30 s case is its own test: Spring's default clock skew is 60 s)
        tokens.put("expired", mint(encoder, ISSUER, "access", "staff-api", sub, tenant.id(), sid, perms, Instant.now().minusSeconds(600)));
        // 8: other tenant: the staff token of a REAL tenant is exercised separately (crossTenant...); here a token for a tenant that does not exist
        tokens.put("other_tenant_nonexistent", mint(encoder, ISSUER, "access", "staff-api", sub, UUID.randomUUID(), UUID.randomUUID().toString(), perms, ok));
        // 9: other issuer: perfect claims signed by a foreign key; and the right key with a wrong iss claim
        tokens.put("other_issuer/foreign_key", mint(foreignEnc, ISSUER, "access", "staff-api", sub, tenant.id(), sid, perms, ok));
        tokens.put("other_issuer/iss_claim", mint(encoder, "https://evil.example", "access", "staff-api", sub, tenant.id(), sid, perms, ok));
        // extras: staff access token on the platform plane and a wrong-audience access token
        tokens.put("access_aud_mfa", mint(encoder, ISSUER, "access", "mfa", sub, tenant.id(), sid, perms, ok));
        tokens.put("no_typ_no_aud", mint(encoder, ISSUER, null, null, sub, tenant.id(), sid, perms, ok));

        List<Op> all = allOps();
        // protected = answers an anonymous call with 401
        List<Op> prot = new ArrayList<>();
        List<String> publicOps = new ArrayList<>();
        for (Op op : all) {
            if (call(op, null, Map.of()) == 401) prot.add(op); else publicOps.add(op.label());
        }
        assertThat(prot.size()).as("protected operations enumerated").isGreaterThan(200);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(access), Map.class).getStatusCode()).as("control before").isEqualTo(HttpStatus.OK);

        ConcurrentLinkedQueue<String> bad = new ConcurrentLinkedQueue<>();
        tokens.entrySet().parallelStream().forEach(e -> {
            for (Op op : prot) {
                if (own.getOrDefault(e.getKey(), p -> false).test(op.path())) continue;
                int s = call(op, e.getValue(), Map.of());
                if (s != 401) bad.add("token '" + e.getKey() + "' accepted (HTTP " + s + ") on " + op.label() + " [" + op.handler() + "]");
            }
        });
        TreeSet<String> pub = new TreeSet<>(publicOps);
        Files.writeString(OUT, "ops_total=" + all.size() + " protected=" + prot.size() + " public=" + pub.size() + "\nPUBLIC:\n" + String.join("\n", pub)
                + "\nVIOLATIONS:\n" + String.join("\n", new TreeSet<>(bad)) + "\n");
        assertThat(new TreeSet<>(bad)).as("tokens accepted where only a staff access token is valid").isEmpty();
        // positive control on a DIFFERENT staff member (the exempt mfa/enroll call above legitimately changes the minted subject's session)
        SeededStaff control = seedStaff(tenant, fullRole, "q40c@a.com", "+919000004009", false);
        String controlToken = loginAndGetAccessToken(control);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(controlToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // staff access token must not open the platform plane
        assertThat(call(new Op(HttpMethod.GET, "/v1/platform/tenants", "x"), controlToken, Map.of())).isEqualTo(401);

    }

    /** Story: "expired" must be 401. Spring's JwtTimestampValidator tolerates 60 s of skew by default, so a token that expired 30 s ago still opens the API. */
    @Test
    void anAccessTokenThatExpiredThirtySecondsAgoIsRefused() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "q40exp@a.com", "+919000004010", false);
        String sid = claim(loginAndGetAccessToken(staff), "sid");
        String stale = mint(encoder, ISSUER, "access", "staff-api", staff.id(), tenant.id(), sid, List.of(), Instant.now().minusSeconds(30));
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(stale), Map.class).getStatusCode().value()).as("token expired 30 s ago").isEqualTo(401);
    }

    @Test
    void realMfaPendingFromAPinLoginAndAStepUpTokenCannotActivateBreakGlass() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedBreakGlassDoctorRole(tenant.id()), "q40bg@a.com", "+919000004002", true);
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("accessToken")).as("PIN alone must not yield an access token when MFA is on").isNull();
        String challenge = (String) login.getBody().get("challengeId");
        Map<String, String> reason = Map.of("reason", "urgent cover for the ward, patient deteriorating");
        for (String path : List.of("/v1/auth/break-glass/activate", "/v1/staff", "/v1/auth/sessions", "/v1/auth/mfa/enroll", "/v1/auth/unlock")) {
            HttpMethod m = path.equals("/v1/staff") || path.equals("/v1/auth/sessions") ? HttpMethod.GET : HttpMethod.POST;
            ResponseEntity<String> r = http.exchange(url(path), m, authedJsonBody(challenge, reason), String.class);
            assertThat(r.getStatusCode().value()).as("mfa_pending on " + m + " " + path).isEqualTo(401);
            assertThat(r.getBody() == null ? "" : r.getBody()).as("no data leaked").doesNotContain("break_glass").doesNotContain("grantId");
        }
        // no break-glass grant was created by the failed attempts
        Integer grants = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM break_glass_grants", Integer.class));
        assertThat(grants).isZero();
    }

    @Test
    void aRealTenantAToken_cannotReadTenantBData() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff sa = seedStaff(a, seedFullAccessRole(a.id()), "q40a@a.com", "+919000004003", false);
        UUID bPatient = UUID.randomUUID();
        inTenantTx(b.id(), () -> jdbc.update(
                "INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Other Clinic Patient','+919000004004','1990-01-01','other')",
                bPatient, b.id()));
        String tokenA = loginAndGetAccessToken(sa);
        ResponseEntity<String> r = exchange("/v1/patients/" + bPatient, HttpMethod.GET, authed(tokenA), String.class);
        assertThat(r.getStatusCode().value()).as("tenant A token against tenant B patient").isIn(403, 404);
        assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain("Other Clinic Patient");
    }

    @Test
    void enumerationIsReflectionBasedNotAHardcodedList() throws Exception {
        String src = Files.readString(Path.of("src/test/java/com/nabd/hms/security/TokenMisuseMatrixTest.java"));
        assertThat(src).contains("RequestMappingHandlerMapping").contains("getHandlerMethods()");
        Matcher m = Pattern.compile("\"(/v1/[^\"]*)\"").matcher(src);
        TreeSet<String> literals = new TreeSet<>();
        while (m.find()) literals.add(m.group(1));
        // only the probe path and the three own-endpoint allow-list groups may be named
        assertThat(literals).as("path literals in the engineer's matrix").isSubsetOf(
                "/v1/auth/break-glass/activate", "/v1/auth/mfa/enroll", "/v1/auth/mfa/confirm", "/v1/owners/me/workspaces",
                "/v1/owners/workspaces/select", "/v1/platform/", "/v1/");
        // coverage of the engineer's enumeration: it probes only the first pattern and first verb of each mapping
        int full = allOps().size();
        int firstOnly = 0;
        for (var e : mappings.getHandlerMethods().entrySet()) {
            if (e.getValue().getBeanType().getName().startsWith("com.nabd.hms.") && !e.getKey().getPathPatternsCondition().getPatternValues().isEmpty()
                    && e.getKey().getPathPatternsCondition().getPatternValues().iterator().next().startsWith("/v1/")) firstOnly++;
        }
        Files.writeString(Path.of("target/s9qa-ops040-coverage.txt"), "allOps=" + full + " engineerFirstVerbFirstPatternOps=" + firstOnly + "\n");
        assertThat(full).isGreaterThanOrEqualTo(firstOnly);
    }
}
