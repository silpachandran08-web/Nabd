package com.nabd.hms.auth;

import com.nabd.hms.NabdHmsApplication;
import com.nabd.hms.config.SigningKeyProvider;
import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S11 QA (independent): the REAL application under SPRING_PROFILES_ACTIVE=prod. A complete prod config boots; removing
 * exactly one safeguard (PLT-005 enforce flag, PLT-044 keyring, PLT-006 phone HMAC key) makes startup fail on that guard.
 */
class S11QaProdBootGuardsTest extends ApiTestBase {

    @Autowired
    Environment env;

    private List<String> prodArgs() throws Exception {
        String jwks = new JWKSet(new ArrayList<JWK>(List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen()))).toString(false);
        byte[] pepper = new byte[32];
        new java.security.SecureRandom().nextBytes(pepper);
        return new ArrayList<>(List.of(
                "--spring.profiles.active=prod", "--server.port=0", "--management.server.port=0", "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
                "--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
                "--spring.datasource.password=" + env.getProperty("spring.datasource.password"),
                "--app.crypto.key=" + CRYPTO_KEY_B64, "--app.crypto.keyring=1:" + CRYPTO_KEY_B64, "--app.crypto.active-kid=1",
                "--app.jwt.keys-json=" + jwks,
                "--app.auth.otp.phone-hmac-key=" + Base64.getEncoder().encodeToString(pepper),
                // src/test/resources/application.properties sets enforce-privileged=false for every boot on the test classpath
                "--app.auth.mfa.enforce-privileged=true",
                "--app.outbox.poll-interval-ms=" + Integer.MAX_VALUE,
                "--app.registry.poll-interval-ms=" + Integer.MAX_VALUE,
                "--app.registry.reconcile-cron=-",
                "--spring.datasource.hikari.maximum-pool-size=2"));
    }

    private static String[] with(List<String> args, String... overrides) {
        List<String> out = new ArrayList<>(args);
        for (String o : overrides) {
            String key = o.substring(0, o.indexOf('=') + 1);
            out.removeIf(a -> a.startsWith(key));
            if (!o.endsWith("=")) out.add(o);
        }
        return out.toArray(String[]::new);
    }

    @Test
    void completeProdConfigBootsWithEnforcementOn() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(NabdHmsApplication.class).run(
                with(prodArgs(), "--app.auth.mfa.enforce-privileged=true"))) {
            assertThat(ctx.getBean(MfaPolicy.class).enforced()).isTrue();
        }
    }

    @Test
    void prodWithMfaEnforcementOffRefusesToBoot() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NabdHmsApplication.class).run(
                with(prodArgs(), "--app.auth.mfa.enforce-privileged=false")))
                .hasStackTraceContaining("app.auth.mfa.enforce-privileged must not be false in the prod profile");
    }

    @Test
    void prodWithMfaEnforcementOffViaEnvStyleValueAlsoRefuses() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NabdHmsApplication.class).run(
                with(prodArgs(), "--app.auth.mfa.enforce-privileged=FALSE")))
                .hasStackTraceContaining("must not be false in the prod profile");
    }

    @Test
    void prodWithoutKeyringRefusesToBoot() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NabdHmsApplication.class).run(
                with(prodArgs(), "--app.crypto.keyring=", "--app.crypto.active-kid=")))
                .hasStackTraceContaining("Refusing to boot under the prod profile without a keyring");
    }

    @Test
    void prodWithoutOtpPhoneHmacKeyRefusesToBoot() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NabdHmsApplication.class).run(
                with(prodArgs(), "--app.auth.otp.phone-hmac-key=")))
                .hasStackTraceContaining("must be set in the prod profile (PLT-006)");
    }
}
