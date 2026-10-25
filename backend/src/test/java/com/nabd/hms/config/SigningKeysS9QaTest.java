package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA for PLT-002 (signing keys, JWKS, iss/aud/kid validation, prod fail-fast, rotation overlap). */
class SigningKeysS9QaTest extends ApiTestBase {

    private static final String ISS = com.nabd.hms.shared.security.TokenPopulation.STAFF.issuer();

    @Autowired SigningKeyProvider qaKeys;

    private static RSAKey key() {
        try { return SigningKeyProvider.gen(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String jwks(RSAKey... ks) {
        return new JWKSet(new ArrayList<com.nimbusds.jose.jwk.JWK>(List.of(ks))).toString(false);
    }

    /** PLT-009: prod also needs the owner/platform key sets and the internal public JWKS. */
    private static ApplicationContextRunner withOtherPopulations(ApplicationContextRunner r) {
        return r.withPropertyValues("app.jwt.owner.keys-json=" + jwks(key(), key()), "app.jwt.platform.keys-json=" + jwks(key(), key()),
                "app.jwt.internal.jwks-json=" + new JWKSet(new ArrayList<com.nimbusds.jose.jwk.JWK>(List.of(key()))).toString(true));
    }

    private static ApplicationContextRunner runner(String keysJson, String... profiles) {
        ApplicationContextRunner r = new ApplicationContextRunner().withUserConfiguration(JwtConfig.class);
        if (keysJson != null) r = r.withPropertyValues("app.jwt.keys-json=" + keysJson);
        if (profiles.length > 0) r = r.withInitializer(c -> c.getEnvironment().setActiveProfiles(profiles));
        if (java.util.Arrays.asList(profiles).contains("prod")) r = withOtherPopulations(r);
        return r;
    }

    private static String mint(JwtEncoder enc, String aud) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuedAt(now).expiresAt(now.plusSeconds(600)).subject("s");
        if (aud != null) b.audience(List.of(aud));
        return enc.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private static String sign(RSAKey k, String kid, String typ, String iss, String aud, long expInSec) throws Exception {
        JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.RS256);
        if (kid != null) h.keyID(kid);
        if (typ != null) h.type(new JOSEObjectType(typ));
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().subject("s").expirationTime(new Date(System.currentTimeMillis() + expInSec * 1000));
        if (iss != null) c.issuer(iss);
        if (aud != null) c.audience(aud);
        SignedJWT jwt = new SignedJWT(h.build(), c.build());
        jwt.sign(new RSASSASigner(k));
        return jwt.serialize();
    }

    private static boolean accepted(JwtDecoder d, String token) {
        try { d.decode(token); return true; } catch (JwtException e) { return false; }
    }

    // ---- AC1/AC2: two replicas sharing the key config accept each other's tokens; rolling restart ----
    @Test
    void ac1_ac2_twoContextsWithSameKeysAcceptEachOthersTokens_andRestartKeepsTokens() {
        String json = jwks(key(), key());
        runner(json).run(a -> runner(json).run(b -> {
            assertThat(accepted(b.getBean(JwtDecoder.class), mint(a.getBean(JwtEncoder.class), "nabd-api"))).isTrue();
            assertThat(accepted(a.getBean(JwtDecoder.class), mint(b.getBean(JwtEncoder.class), "nabd-api"))).isTrue();
        }));
        // "rolling restart": token minted before, decoded by a brand new context with the same secret
        String[] token = new String[1];
        runner(json).run(a -> token[0] = mint(a.getBean(JwtEncoder.class), "nabd-api"));
        runner(json).run(b -> assertThat(accepted(b.getBean(JwtDecoder.class), token[0])).isTrue());
    }

    @Test
    void ac1_negativeControl_ephemeralKeysDoNotCrossContexts() {
        String[] token = new String[1];
        runner(null).run(a -> token[0] = mint(a.getBean(JwtEncoder.class), "nabd-api"));
        runner(null).run(b -> assertThat(accepted(b.getBean(JwtDecoder.class), token[0])).as("fresh random keys per boot").isFalse());
    }

    // ---- AC3: JWKS -------------------------------------------------------------------------------
    @Test
    @SuppressWarnings("unchecked")
    void ac3_jwksIsPublicHasExactlyTwoKeysAndNoPrivateMaterial() {
        ResponseEntity<Map> r = http.getForEntity(url("/.well-known/jwks.json"), Map.class); // no Authorization header
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).contains("json");
        String cc = r.getHeaders().getCacheControl();
        assertThat(cc).contains("max-age=300").contains("public");
        List<Map<String, Object>> keys = (List<Map<String, Object>>) r.getBody().get("keys");
        assertThat(keys).hasSize(2);
        assertThat(keys.stream().map(k -> k.get("kid")).distinct().count()).isEqualTo(2);
        for (Map<String, Object> k : keys) {
            assertThat(k.get("kty")).isEqualTo("RSA");
            assertThat(k.get("use")).isEqualTo("sig");
            assertThat(k.get("alg")).isEqualTo("RS256");
            assertThat(k).containsKeys("kid", "n", "e");
            assertThat(k.keySet()).doesNotContain("d", "p", "q", "dp", "dq", "qi", "oth", "k");
        }
        String raw = http.getForObject(url("/.well-known/jwks.json"), String.class);
        for (RSAKey priv : qaKeys.keys()) {
            assertThat(raw).doesNotContain(priv.getPrivateExponent().toString()).doesNotContain("\"d\"");
        }
        // kid published = kid of freshly issued tokens
        String access = loginAndGetAccessToken(seedStaffForJwks());
        String kid = (String) com.nimbusds.jwt.SignedJWT.class.cast(parse(access)).getHeader().getKeyID();
        assertThat(keys.stream().map(k -> k.get("kid"))).contains(kid);
        // POST/other methods are not a way in
        assertThat(http.exchange(url("/.well-known/jwks.json"), HttpMethod.DELETE, new HttpEntity<>(new HttpHeaders()), String.class)
                .getStatusCode().is2xxSuccessful()).isFalse();
    }

    private SeededStaff seedStaffForJwks() {
        SeededTenant t = seedTenant();
        return seedStaff(t, seedFullAccessRole(t.id()), "qa-jwks@a.com", "+919500000001", false);
    }

    private static SignedJWT parse(String s) {
        try { return SignedJWT.parse(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void nfr_jwksP95Under20ms() {
        for (int i = 0; i < 30; i++) http.getForObject(url("/.well-known/jwks.json"), String.class); // warm up
        long[] ns = new long[200];
        for (int i = 0; i < ns.length; i++) {
            long t0 = System.nanoTime();
            http.getForObject(url("/.well-known/jwks.json"), String.class);
            ns[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(ns);
        double p95 = ns[(int) (ns.length * 0.95)] / 1e6;
        System.out.println("QA-JWKS p95_ms=" + p95);
        assertThat(p95).isLessThan(20.0);
    }

    // ---- AC4: wrong iss / aud / unknown kid / missing typ ---------------------------------------------
    @Test
    void ac4_decoderRejectsWrongIssAudKidTypAndAlgConfusion() throws Exception {
        RSAKey a = key(), b = key();
        String json = jwks(a, b);
        runner(json).run(ctx -> {
            JwtDecoder d = ctx.getBean(JwtDecoder.class);
            try {
                assertThat(accepted(d, sign(a, a.getKeyID(), "JWT", ISS, "nabd-api", 300))).as("control: valid").isTrue();
                assertThat(accepted(d, sign(b, b.getKeyID(), "JWT", ISS, "nabd-api", 300))).as("control: second kid valid").isTrue();
                assertThat(accepted(d, sign(a, a.getKeyID(), "JWT", "https://evil.example", "nabd-api", 300))).as("wrong iss").isFalse();
                assertThat(accepted(d, sign(a, a.getKeyID(), "JWT", null, "nabd-api", 300))).as("missing iss").isFalse();
                assertThat(accepted(d, sign(a, a.getKeyID(), "JWT", ISS, null, 300))).as("missing aud").isFalse();
                RSAKey stranger = key();
                assertThat(accepted(d, sign(stranger, stranger.getKeyID(), "JWT", ISS, "nabd-api", 300))).as("unknown kid").isFalse();
                assertThat(accepted(d, sign(stranger, a.getKeyID(), "JWT", ISS, "nabd-api", 300))).as("known kid, foreign key material").isFalse();
                assertThat(accepted(d, sign(a, null, "JWT", ISS, "nabd-api", 300))).as("missing kid").isFalse();
                assertThat(accepted(d, sign(a, a.getKeyID(), null, ISS, "nabd-api", 300))).as("missing typ header").isFalse();
                assertThat(accepted(d, sign(a, a.getKeyID(), "at+jwt", ISS, "nabd-api", 300))).as("odd typ header").isFalse();
                assertThat(accepted(d, sign(a, a.getKeyID(), "JWT", ISS, "nabd-api", -120))).as("expired").isFalse();
                // alg confusion: HS256 signed with the public modulus bytes as secret
                byte[] secret = java.util.Arrays.copyOf(a.toPublicJWK().toJSONString().getBytes(), 64);
                SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(a.getKeyID()).type(JOSEObjectType.JWT).build(),
                        new JWTClaimsSet.Builder().issuer(ISS).audience("nabd-api").subject("s").expirationTime(new Date(System.currentTimeMillis() + 300_000)).build());
                hs.sign(new MACSigner(secret));
                assertThat(accepted(d, hs.serialize())).as("HS256 confusion").isFalse();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void ac4_http_tokenWithMissingTypOrForeignKeyIs401() throws Exception {
        RSAKey real = qaKeys.keys().get(0);
        RSAKey stranger = key();
        String foreign = sign(stranger, real.getKeyID(), "JWT", ISS, "staff-api", 300);
        String unknownKid = sign(stranger, stranger.getKeyID(), "JWT", ISS, "staff-api", 300);
        String noTypClaimSigned = sign(real, real.getKeyID(), "JWT", ISS, "staff-api", 300); // properly signed, but no typ claim
        for (String t : List.of(foreign, unknownKid, noTypClaimSigned)) {
            assertThat(exchange("/v1/auth/sessions", HttpMethod.GET, authed(t), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---- AC5: prod fails to boot without keys ------------------------------------------------------
    @Test
    void ac5_prodWithoutKeysFailsToStartNamingTheSecret_nonProdStarts() {
        runner(null, "prod").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("JWT_KEYS_FILE").hasStackTraceContaining("JWT_KEYS_JSON");
        });
        runner(null, "dev").run(ctx -> assertThat(ctx).hasNotFailed());
        runner(jwks(key(), key()), "prod").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void ac5_prodWithKeysFileBoots_andBadConfigFailsClosedWithoutLeakingKeyMaterial() throws Exception {
        RSAKey a = key(), b = key();
        Path f = Files.createTempFile("qa-jwks", ".json");
        try {
            Files.writeString(f, jwks(a, b));
            withOtherPopulations(new ApplicationContextRunner().withUserConfiguration(JwtConfig.class)).withPropertyValues("app.jwt.keys-file=" + f)
                    .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod")).run(ctx -> assertThat(ctx).hasNotFailed());
            withOtherPopulations(new ApplicationContextRunner().withUserConfiguration(JwtConfig.class)).withPropertyValues("app.jwt.keys-file=" + f + ".missing")
                    .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod")).run(ctx -> assertThat(ctx).hasFailed());
        } finally {
            Files.deleteIfExists(f);
        }
        String secretD = a.getPrivateExponent().decodeToString();
        List<String> bad = List.of(
                jwks(a),                                   // one key
                jwks(a, b, key()),                         // three keys
                jwks(a, new RSAKey.Builder(b).keyID(a.getKeyID()).build()), // duplicate kid
                new JWKSet(List.of(a.toPublicJWK(), b.toPublicJWK())).toString(), // public only
                "{\"keys\":[" + "not json",
                new JWKSet(List.of(new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).generate(), a)).toString(false).replace("\"kid\"", "\"xkid\"") // no kid
        );
        for (String j : bad) {
            assertThatThrownBy(() -> SigningKeyProvider.of(j)).isInstanceOf(IllegalStateException.class)
                    .satisfies(e -> assertThat(String.valueOf(e.getMessage()) + e.getCause()).doesNotContain(secretD));
        }
        RSAKey weak = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(1024, true).keyID("w").generate();
        assertThatThrownBy(() -> SigningKeyProvider.of(jwks(weak, b))).isInstanceOf(IllegalStateException.class).hasMessageContaining("2048");
    }

    /** The shipped application.yml itself must map the documented names (PLT-002 fix). */
    @Test
    void ac5_applicationYmlMapsDocumentedEnvVars() throws Exception {
        String json = jwks(key(), key());
        StandardEnvironment env = new StandardEnvironment();
        for (var ps : new org.springframework.boot.env.YamlPropertySourceLoader().load("app",
                new org.springframework.core.io.ClassPathResource("application.yml"))) env.getPropertySources().addLast(ps);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("qaEnv2", Map.of("JWT_KEYS_JSON", json)));
        env.setActiveProfiles("prod");
        assertThat(SigningKeyProvider.from(env)).isNotNull();
    }

    // ---- AC6: rotation overlap ------------------------------------------------------------------------------
    @Test
    void ac6_rotationKeepsPreviousKidValidThenRetires() {
        RSAKey a = key(), b = key(), c = key();
        String[] oldToken = new String[1];
        runner(jwks(a, b)).run(ctx -> oldToken[0] = mint(ctx.getBean(JwtEncoder.class), "nabd-api"));      // signed by A
        runner(jwks(b, a)).run(ctx -> {                                                                    // promote B, A is previous
            assertThat(accepted(ctx.getBean(JwtDecoder.class), oldToken[0])).as("previous kid still valid").isTrue();
            assertThat(ctx.getBean(SigningKeyProvider.class).keys().get(0).getKeyID()).isEqualTo(b.getKeyID());
        });
        String[] newToken = new String[1];
        runner(jwks(b, a)).run(ctx -> newToken[0] = mint(ctx.getBean(JwtEncoder.class), "nabd-api"));      // signed by B
        runner(jwks(a, b)).run(ctx -> assertThat(accepted(ctx.getBean(JwtDecoder.class), newToken[0])).as("old replica accepts next kid").isTrue());
        runner(jwks(c, b)).run(ctx -> assertThat(accepted(ctx.getBean(JwtDecoder.class), oldToken[0])).as("retired kid rejected").isFalse());
    }
}
