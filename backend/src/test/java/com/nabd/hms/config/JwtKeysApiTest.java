package com.nabd.hms.config;

import com.nabd.hms.NabdHmsApplication;
import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeysApiTest extends ApiTestBase {

    static final String KEYS_JSON;

    static {
        try {
            KEYS_JSON = new JWKSet(new ArrayList<JWK>(List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen()))).toString(false);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("app.jwt.keys-json", () -> KEYS_JSON);
    }

    @org.springframework.beans.factory.annotation.Autowired
    Environment env;

    private ConfigurableApplicationContext replica(boolean sharedKeys, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
                "--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
                "--spring.datasource.password=" + env.getProperty("spring.datasource.password"),
                "--app.crypto.key=" + env.getProperty("app.crypto.key"),
                "--app.outbox.poll-interval-ms=" + Integer.MAX_VALUE,
                "--app.auth.rate-limit-per-ip-per-minute=100000"));
        if (sharedKeys) args.add("--app.jwt.keys-json=" + KEYS_JSON);
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(NabdHmsApplication.class).run(args.toArray(new String[0]));
    }

    private ResponseEntity<String> sessions(int p, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange("http://localhost:" + p + "/v1/auth/sessions", HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void jwksPublishesTwoPublicKeysOnly() {
        ResponseEntity<String> resp = http.getForEntity(url("/.well-known/jwks.json"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getCacheControl()).contains("max-age=300");
        Map<?, ?> body = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                readJson(resp.getBody()), Map.class);
        List<?> ks = (List<?>) body.get("keys");
        assertThat(ks).hasSize(2);
        for (Object k : ks) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) k;
            assertThat(m).containsKeys("kid", "kty", "n", "e").containsEntry("use", "sig").containsEntry("alg", "RS256");
            assertThat(m.keySet()).doesNotContain("d", "p", "q", "dp", "dq", "qi");
        }
    }

    private Object readJson(String s) {
        try {
            return objectMapper.readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void tokenFromReplicaAWorksOnReplicaBAndSurvivesRollingRestart() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "r@a.com", "+919000000555", false);
        String token = loginAndGetAccessToken(staff); // signed by this context ("replica A")
        try (ConfigurableApplicationContext b = replica(true)) {
            int bPort = Integer.parseInt(b.getEnvironment().getProperty("local.server.port"));
            assertThat(sessions(bPort, token).getStatusCode()).isEqualTo(HttpStatus.OK);
            // "restart" of A: a brand new context on the same key secret still accepts the old token
            try (ConfigurableApplicationContext a2 = replica(true)) {
                int p2 = Integer.parseInt(a2.getEnvironment().getProperty("local.server.port"));
                assertThat(sessions(p2, token).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Test
    void tokenFromUnrelatedKeysIsRefused() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "r2@a.com", "+919000000556", false);
        String token = loginAndGetAccessToken(staff);
        // dev profile without configured keys generates its own: a different issuer of keys must reject
        try (ConfigurableApplicationContext other = replica(false)) {
            int p = Integer.parseInt(other.getEnvironment().getProperty("local.server.port"));
            assertThat(sessions(p, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void prodProfileWithoutKeysFailsToBoot() {
        assertThatThrownBy(() -> replica(false, "--spring.profiles.active=prod", // PLT-044: prod also needs a keyring; give one so the JWT guard is what fails
                "--app.crypto.keyring=1:" + env.getProperty("app.crypto.key")).close())
                .hasStackTraceContaining("JWT_KEYS_FILE");
    }
}
