package com.nabd.hms.config;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenPopulation;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLT-009: a token of one population (staff, owner, platform, internal) is a 401 on every other population's URL prefix, on every
 * mapped operation (enumerated by reflection, so a new endpoint is covered), a token signed by another population's key is
 * refused even when its claims are perfect, and each population publishes and rotates its own keys.
 */
class CrossPopulationReplayMatrixTest extends ApiTestBase {

    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired JwtEncoder encoder;
    @Autowired SigningKeyProvider staffKeys;
    @Autowired PopulationKeys populationKeys;

    private enum Prefix { CLINIC, PLATFORM, OWNER, INTERNAL }

    private record Op(HttpMethod method, String path, Prefix prefix) {
        String label() {
            return method + " " + path;
        }
    }

    private static Prefix prefixOf(String p) {
        return p.startsWith("/v1/platform/") ? Prefix.PLATFORM : p.startsWith("/v1/owners/") ? Prefix.OWNER
                : p.startsWith("/internal/v1/") ? Prefix.INTERNAL : Prefix.CLINIC;
    }

    private List<Op> operations() {
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().startsWith("com.nabd.hms")) continue;
            var methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/v1/") && !pattern.startsWith("/internal/v1/")) continue;
                String path = pattern.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
                for (var m : methods.isEmpty() ? Set.of(org.springframework.web.bind.annotation.RequestMethod.GET) : methods) {
                    ops.add(new Op(HttpMethod.valueOf(m.name()), path, prefixOf(pattern)));
                }
            }
        }
        return ops;
    }

    private int call(Op op, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        if (token != null) h.setBearerAuth(token);
        return http.exchange(url(op.path()), op.method(), new HttpEntity<>("{}", h), String.class).getStatusCode().value();
    }

    private Map<String, String> tokens() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "xp-" + UUID.randomUUID() + "@a.com", "+919000" + (long) (Math.random() * 900000 + 100000), false);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("staff", loginAndGetAccessToken(staff));
        t.put("operator", platformLoginAndGetAccessToken(seedOperator("xp-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false)));
        t.put("owner_select", ownerLoginPendingToken(seedOwner("xp-" + UUID.randomUUID() + "@owner.test")));
        t.put("internal", InternalTestTokens.mint(encoder, "patient-access-edge", tenant.id()));
        return t;
    }

    private static final Map<String, Set<Prefix>> MUST_BE_401 = Map.of(
            "staff", Set.of(Prefix.PLATFORM, Prefix.INTERNAL),
            "operator", Set.of(Prefix.CLINIC), // operator tokens may still open the operator-driven endpoints under /internal/v1 (CLN-004, COM-012)
            "owner_select", Set.of(Prefix.CLINIC, Prefix.PLATFORM, Prefix.INTERNAL),
            "internal", Set.of(Prefix.CLINIC, Prefix.PLATFORM));

    @Test
    void everyPopulationTokenIsRefusedOnEveryOtherPopulationsPrefix() {
        List<Op> ops = operations();
        assertThat(ops.size()).as("discovered operations").isGreaterThan(100);
        assertThat(ops.stream().map(Op::prefix).distinct()).as("all four prefixes are exercised").contains(Prefix.CLINIC, Prefix.PLATFORM, Prefix.INTERNAL);
        Map<String, String> tokens = tokens();
        List<String> accepted = new ArrayList<>();
        int checked = 0;
        for (Op op : ops) {
            if (call(op, null) != 401) continue; // answers an anonymous call with anything but 401: public (login, JWKS, webhook), nothing to replay
            for (var tok : tokens.entrySet()) {
                if (!MUST_BE_401.get(tok.getKey()).contains(op.prefix())) continue;
                if (tok.getKey().equals("owner_select") && Set.of("/v1/owners/me/workspaces", "/v1/owners/workspaces/select").contains(op.path())) continue;
                checked++;
                int status = call(op, tok.getValue());
                if (status != 401) accepted.add(tok.getKey() + " token on " + op.label() + " -> " + status);
            }
        }
        assertThat(checked).as("replay attempts made").isGreaterThan(300);
        assertThat(accepted).as("cross-population replays that were accepted").isEmpty();
    }

    @Test
    void ownerSelectOpensOnlyItsOwnTwoEndpointsAndOwnerPrefixHasNoOtherDoor() {
        String ownerSelect = tokens().get("owner_select");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerSelect);
        assertThat(http.exchange(url("/v1/owners/me/workspaces"), HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode())
                .as("its own endpoint").isEqualTo(HttpStatus.OK);
        // an unmapped path under /v1/owners/ is closed to every token, staff included
        for (String token : List.of(ownerSelect, tokens().get("staff"))) {
            HttpHeaders hh = new HttpHeaders();
            hh.setBearerAuth(token);
            assertThat(http.exchange(url("/v1/owners/anything-else"), HttpMethod.GET, new HttpEntity<>(hh), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ---- keys: a perfect token signed by the wrong population's key is refused -----------------------------------

    private static String sign(RSAKey key, JwtClaimsSet claims) {
        JwtEncoder enc = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return enc.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims)).getTokenValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String token) throws Exception {
        return objectMapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
    }

    private JwtClaimsSet reissue(Map<String, Object> real, TokenPopulation as, Audience aud) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(as.issuer()).issuedAt(now).expiresAt(now.plusSeconds(300)).subject((String) real.get("sub"));
        real.forEach((k, v) -> {
            if (Set.of("sid", "tenantId", "permissions", "role", "name").contains(k)) b.claim(k, v);
        });
        TokenType.ACCESS.stamp(b, aud);
        return b.build();
    }

    @Test
    void aTokenSignedByAnotherPopulationsKeyIsRefusedEvenWithPerfectClaims() throws Exception {
        Map<String, String> t = tokens();
        RSAKey staffKey = populationKeys.signing(TokenPopulation.STAFF);
        RSAKey platformKey = populationKeys.signing(TokenPopulation.PLATFORM);
        RSAKey ownerKey = populationKeys.signing(TokenPopulation.OWNER);
        assertThat(staffKey.getKeyID()).isNotEqualTo(platformKey.getKeyID()).isNotEqualTo(ownerKey.getKeyID());

        Map<String, Object> staffClaims = payload(t.get("staff"));
        Map<String, Object> operatorClaims = payload(t.get("operator"));
        assertThat(staffClaims.get("iss")).isEqualTo(TokenPopulation.STAFF.issuer());
        assertThat(operatorClaims.get("iss")).isEqualTo(TokenPopulation.PLATFORM.issuer());

        // sanity: the genuine tokens work on their own prefix
        assertThat(status("/v1/auth/sessions", t.get("staff"))).isEqualTo(200);
        assertThat(status("/v1/platform/plans", t.get("operator"))).isNotEqualTo(401);
        // claims of a real operator session, claiming the platform issuer, signed with the STAFF key
        assertThat(status("/v1/platform/plans", sign(staffKey, reissue(operatorClaims, TokenPopulation.PLATFORM, Audience.PLATFORM_API)))).isEqualTo(401);
        // claims of a real staff session, claiming the staff issuer, signed with the PLATFORM / OWNER key
        assertThat(status("/v1/auth/sessions", sign(platformKey, reissue(staffClaims, TokenPopulation.STAFF, Audience.STAFF_API)))).isEqualTo(401);
        assertThat(status("/v1/auth/sessions", sign(ownerKey, reissue(staffClaims, TokenPopulation.STAFF, Audience.STAFF_API)))).isEqualTo(401);
        // staff claims presented under the platform issuer/key set
        assertThat(status("/v1/auth/sessions", sign(platformKey, reissue(staffClaims, TokenPopulation.PLATFORM, Audience.STAFF_API)))).isEqualTo(401);
        // the pre-PLT-009 shared issuer no longer verifies, even signed by the right key
        JwtClaimsSet legacy = JwtClaimsSet.from(reissue(staffClaims, TokenPopulation.STAFF, Audience.STAFF_API)).issuer(TokenPopulation.LEGACY_ISSUER).build();
        assertThat(status("/v1/auth/sessions", sign(staffKey, legacy))).isEqualTo(401);
        // a genuine staff token re-signed with its own key is accepted (the harness itself is sound)
        assertThat(status("/v1/auth/sessions", sign(staffKey, reissue(staffClaims, TokenPopulation.STAFF, Audience.STAFF_API)))).isEqualTo(200);
    }

    private int status(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode().value();
    }

    // ---- JWKS per population ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> kids(String path) throws Exception {
        ResponseEntity<String> r = http.getForEntity(url(path), String.class);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getCacheControl()).contains("max-age=300");
        Map<String, Object> body = objectMapper.readValue(r.getBody(), Map.class);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        assertThat(keys).as(path).hasSize(2);
        keys.forEach(k -> assertThat(k.keySet()).doesNotContain("d", "p", "q", "dp", "dq", "qi"));
        return keys.stream().map(k -> (String) k.get("kid")).toList();
    }

    @Test
    void eachPopulationPublishesItsOwnTwoActiveKids() throws Exception {
        List<String> staff = kids("/.well-known/jwks/staff.json");
        List<String> owner = kids("/.well-known/jwks/owner.json");
        List<String> platform = kids("/.well-known/jwks/platform.json");
        assertThat(kids("/.well-known/jwks.json")).as("legacy path is the staff set").isEqualTo(staff);
        assertThat(kids("/.well-known/jwks.json?population=owner")).isEqualTo(owner);
        assertThat(kids("/.well-known/jwks.json?population=platform")).isEqualTo(platform);
        assertThat(java.util.stream.Stream.of(staff, owner, platform).flatMap(List::stream).collect(Collectors.toSet())).as("six distinct kids").hasSize(6);
        assertThat(staff.get(0)).isEqualTo(staffKeys.keys().get(0).getKeyID());
        for (String p : List.of("/.well-known/jwks/internal.json", "/.well-known/jwks/nope.json")) {
            assertThat(http.getForEntity(url(p), String.class).getStatusCode()).as(p).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static String jwks() throws Exception {
        return new JWKSet(new ArrayList<JWK>(List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen()))).toString(false);
    }

    private static MockEnvironment env(String owner, String platform, String internalPublic, String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        if (owner != null) e.setProperty("app.jwt.owner.keys-json", owner);
        if (platform != null) e.setProperty("app.jwt.platform.keys-json", platform);
        if (internalPublic != null) e.setProperty("app.jwt.internal.jwks-json", internalPublic);
        return e;
    }

    @Test
    void rotatingOnePopulationsKeysLeavesTheOthersUntouched() throws Exception {
        String staff = jwks(), owner1 = jwks(), owner2 = jwks(), platform = jwks();
        PopulationKeys before = new PopulationKeys(env(owner1, platform, null), SigningKeyProvider.of(staff));
        PopulationKeys after = new PopulationKeys(env(owner2, platform, null), SigningKeyProvider.of(staff)); // only the owner secret rotated
        assertThat(after.publicSet(TokenPopulation.STAFF).toString()).isEqualTo(before.publicSet(TokenPopulation.STAFF).toString());
        assertThat(after.publicSet(TokenPopulation.PLATFORM).toString()).isEqualTo(before.publicSet(TokenPopulation.PLATFORM).toString());
        assertThat(after.publicSet(TokenPopulation.OWNER).toString()).isNotEqualTo(before.publicSet(TokenPopulation.OWNER).toString());
    }

    @Test
    void populationsCannotShareKeys() throws Exception {
        String shared = jwks();
        assertThatThrownBy(() -> new PopulationKeys(env(shared, jwks(), null), SigningKeyProvider.of(shared)))
                .hasMessageContaining("must not share keys");
    }

    @Test
    void prodRefusesToBootWithoutEachPopulationsKeys() throws Exception {
        String staff = jwks(), owner = jwks(), platform = jwks();
        assertThatThrownBy(() -> new PopulationKeys(env(null, platform, null, "prod"), SigningKeyProvider.of(staff)))
                .hasMessageContaining("JWT_OWNER_KEYS_FILE");
        assertThatThrownBy(() -> new PopulationKeys(env(owner, null, null, "prod"), SigningKeyProvider.of(staff)))
                .hasMessageContaining("JWT_PLATFORM_KEYS_FILE");
        assertThatThrownBy(() -> new PopulationKeys(env(owner, platform, null, "prod"), SigningKeyProvider.of(staff)))
                .hasMessageContaining("JWT_INTERNAL_JWKS_FILE");
        // Core holds only the PUBLIC keys of the internal population: it can verify, never mint
        String publicOnly = new JWKSet(new ArrayList<JWK>(List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen()))).toString(true);
        PopulationKeys prod = new PopulationKeys(env(owner, platform, publicOnly, "prod"), SigningKeyProvider.of(staff));
        assertThat(prod.signing(TokenPopulation.INTERNAL)).isNull();
        assertThat(prod.publicSet(TokenPopulation.INTERNAL).getKeys()).hasSize(2);
    }
}
