package com.nabd.hms.security;

import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.TestAbortedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-040 / QA-01 guard: every kind of token against EVERY mapped controller operation (enumerated by reflection,
 * so a new endpoint is covered without editing this file) must get 401, except on the token's own endpoint.
 * Endpoints that answer an anonymous call with anything but 401 are public and skipped (nothing to bypass).
 *
 * Mode: STRICT fails on any violation. REPORT (until PLT-001 makes the decoder type-aware) records violations as
 * skipped tests. Auto-detected by probing mfa_pending on /v1/auth/break-glass/activate; force with
 * -Dtokenmatrix.mode=strict|report.
 */
class TokenMisuseMatrixTest extends ApiTestBase {

    /** token type -> own endpoints (where it is the right credential, so 401 is not expected). */
    private static final Map<String, Predicate<String>> OWN = Map.of(
            "mfa_setup_required", p -> p.equals("/v1/auth/mfa/enroll") || p.equals("/v1/auth/mfa/confirm"),
            "owner_pending", p -> p.equals("/v1/owners/me/workspaces") || p.equals("/v1/owners/workspaces/select"),
            "platform_operator", p -> p.startsWith("/v1/platform/"));
    // mfa_pending and step_up own endpoint (/v1/auth/mfa/verify) is public, so skipped by the anonymous probe.

    private static final String PROBE_PATH = "/v1/auth/break-glass/activate";

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired JwtEncoder encoder;

    record Op(HttpMethod method, String path, String handler) {
        String label() { return method + " " + path; }
    }

    static String violation(String tokenType, Op op, int status) {
        return "TOKEN-MISUSE: token type '" + tokenType + "' was accepted (HTTP " + status + ") on " + op.label()
                + " [" + op.handler() + "]; expected 401";
    }

    List<Op> operations() {
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var paths = e.getKey().getPathPatternsCondition().getPatternValues();
            if (paths.isEmpty() || !e.getValue().getBeanType().getPackageName().startsWith("com.nabd.hms")) continue;
            String path = paths.iterator().next();
            if (!path.startsWith("/v1/")) continue;
            var methods = e.getKey().getMethodsCondition().getMethods();
            HttpMethod m = methods.isEmpty() ? HttpMethod.GET : HttpMethod.valueOf(methods.iterator().next().name());
            ops.add(new Op(m, path.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString()),
                    e.getValue().getBeanType().getSimpleName() + "." + e.getValue().getMethod().getName()));
        }
        return ops;
    }

    int call(Op op, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return http.exchange(url(op.path()), op.method(), new HttpEntity<>("{}", h), String.class).getStatusCode().value();
    }

    String jwt(JwtEncoder enc, Instant exp, String purpose, boolean sessionShaped) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder c = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now.minusSeconds(60))
                .expiresAt(exp).subject(UUID.randomUUID().toString());
        if (purpose != null) c.claim("purpose", purpose);
        if (purpose != null && !purpose.startsWith("owner")) c.claim("tenantId", UUID.randomUUID().toString());
        if (sessionShaped) {
            c.claim("tenantId", UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString())
                    .claim("permissions", List.of("staff:read", "patients:read", "setup:write", "billing:write"));
        }
        return enc.encode(JwtEncoderParameters.from(c.build())).getTokenValue();
    }

    Map<String, String> tokens() throws Exception {
        Instant ok = Instant.now().plusSeconds(300);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair foreign = g.generateKeyPair();
        JwtEncoder foreignEnc = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new RSAKey.Builder((RSAPublicKey) foreign.getPublic()).privateKey((RSAPrivateKey) foreign.getPrivate())
                        .keyID("foreign").build())));
        Map<String, String> t = new LinkedHashMap<>();
        t.put("mfa_pending", jwt(encoder, ok, "mfa_pending", false));
        t.put("mfa_setup_required", jwt(encoder, ok, "mfa_setup_required", false));
        t.put("step_up", jwt(encoder, ok, "step_up", false));
        t.put("owner_pending", jwt(encoder, ok, "owner_pending_workspace", false));
        t.put("platform_operator", platformLoginAndGetAccessToken(
                seedOperator("tm-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false)));
        t.put("refresh_as_access", UUID.randomUUID().toString() + UUID.randomUUID()); // refresh tokens are opaque
        t.put("expired", jwt(encoder, Instant.now().minusSeconds(30), null, true));
        t.put("other_tenant", jwt(encoder, ok, null, true)); // tenant/session that does not exist or is not live
        t.put("other_issuer", jwt(foreignEnc, ok, null, true));
        return t;
    }

    boolean strict(String probeToken) {
        String forced = System.getProperty("tokenmatrix.mode");
        if (forced != null) return forced.equalsIgnoreCase("strict");
        return call(new Op(HttpMethod.POST, PROBE_PATH, "probe"), probeToken) == 401;
    }

    @TestFactory
    List<DynamicTest> everyTokenTypeIsRefusedOnEveryEndpoint() throws Exception {
        List<Op> protectedOps = operations().stream().filter(o -> call(o, null) == 401).toList();
        assertThat(protectedOps).as("endpoint enumeration found the protected operations").hasSizeGreaterThan(20);
        Map<String, String> tokens = tokens();
        boolean strict = strict(tokens.get("mfa_pending"));
        // parallel by token type; each type's results are computed up front, then asserted one dynamic test per operation
        Map<String, Map<Op, Integer>> results = new java.util.concurrent.ConcurrentHashMap<>();
        tokens.entrySet().parallelStream().forEach(e -> {
            Map<Op, Integer> r = new LinkedHashMap<>();
            for (Op op : protectedOps) {
                if (OWN.getOrDefault(e.getKey(), p -> false).test(op.path())) continue;
                r.put(op, call(op, e.getValue()));
            }
            results.put(e.getKey(), r);
        });
        List<DynamicTest> tests = new ArrayList<>();
        results.forEach((type, r) -> r.forEach((op, status) -> tests.add(DynamicTest.dynamicTest(type + " -> " + op.label(), () -> {
            if (status == 401) return;
            String msg = violation(type, op, status);
            if (strict) throw new AssertionError(msg);
            throw new TestAbortedException("REPORT MODE (waits on PLT-001): " + msg);
        }))));
        return tests;
    }

    @Test
    void violationMessageNamesEndpointAndTokenType() {
        String m = violation("mfa_pending", new Op(HttpMethod.POST, PROBE_PATH, "AuthController.activateBreakGlass"), 200);
        assertThat(m).contains("mfa_pending").contains("POST " + PROBE_PATH);
    }

    @Test
    void enumerationSeesBreakGlassAndSkipsNothingUnderV1() {
        assertThat(operations()).extracting(Op::path).contains(PROBE_PATH);
    }
}
