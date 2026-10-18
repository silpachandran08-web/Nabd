package com.nabd.hms.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Port for the JWT signing keys (PLT-002). Returns exactly two RSA keys with private parts: index 0
 * signs new tokens, index 1 is the next/previous key (published and accepted, so rotation overlaps).
 * The real secret-manager adapter only has to materialize the JWKS JSON as a file or env var; that is
 * what {@link #from} reads. Key material is never logged or put in exception messages.
 */
public interface SigningKeyProvider {

    /** Active key first, then the next/previous key. */
    List<RSAKey> keys();

    String MISSING = "JWT signing keys are not configured: set JWT_KEYS_FILE (path of the mounted secret) "
            + "or JWT_KEYS_JSON (a JWKS with 2 RSA private keys). Refusing to boot under the prod profile without them.";

    static SigningKeyProvider from(Environment env) {
        String file = env.getProperty("app.jwt.keys-file", "");
        String json = env.getProperty("app.jwt.keys-json", "");
        try {
            if (!file.isBlank()) return of(Files.readString(Path.of(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("JWT_KEYS_FILE is set but unreadable: " + file);
        }
        if (!json.isBlank()) return of(json);
        if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) throw new IllegalStateException(MISSING);
        return ephemeral();
    }

    static SigningKeyProvider of(String jwksJson) {
        List<RSAKey> keys = new ArrayList<>();
        try {
            for (JWK k : JWKSet.parse(jwksJson).getKeys()) {
                if (!(k instanceof RSAKey rsa) || !rsa.isPrivate() || rsa.getKeyID() == null)
                    throw new IllegalStateException("JWT keys must be RSA private keys with a kid");
                if (rsa.size() < 2048) throw new IllegalStateException("JWT RSA keys must be at least 2048 bits");
                keys.add(new RSAKey.Builder(rsa).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("JWT keys are not a valid JWKS document");
        }
        if (keys.size() != 2) throw new IllegalStateException("JWT keys must contain exactly 2 keys (active + next/previous), found " + keys.size());
        if (keys.get(0).getKeyID().equals(keys.get(1).getKeyID())) throw new IllegalStateException("JWT keys must have distinct kids");
        List<RSAKey> fixed = List.copyOf(keys);
        return () -> fixed;
    }

    /** Dev/test only (any non-prod profile): fresh keys per boot, tokens die on restart. */
    static SigningKeyProvider ephemeral() {
        try {
            List<RSAKey> keys = List.of(gen(), gen());
            return () -> keys;
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("could not generate dev signing keys", e);
        }
    }

    static RSAKey gen() throws com.nimbusds.jose.JOSEException {
        return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).generate();
    }
}
