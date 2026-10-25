package com.nabd.hms.config;

import com.nabd.hms.shared.security.TokenPopulation;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * PLT-009: one key set per token population (staff, owner, platform, internal). Staff/owner/platform hold two RSA private
 * keys (active first, then next/previous) from their own secret; rotating one never touches another. Internal tokens are
 * minted by other services, so Core only needs their PUBLIC keys (app.jwt.internal.jwks-*); outside prod, missing config
 * generates a private pair so tests can mint {@code TokenType.INTERNAL} tokens. Key material is never logged.
 */
public final class PopulationKeys {

    private final Map<TokenPopulation, List<RSAKey>> keys = new EnumMap<>(TokenPopulation.class);

    PopulationKeys(Environment env, SigningKeyProvider staff) {
        keys.put(TokenPopulation.STAFF, staff.keys());
        keys.put(TokenPopulation.OWNER, SigningKeyProvider.from(env, "app.jwt.owner", "JWT_OWNER_KEYS_FILE", "JWT_OWNER_KEYS_JSON").keys());
        keys.put(TokenPopulation.PLATFORM, SigningKeyProvider.from(env, "app.jwt.platform", "JWT_PLATFORM_KEYS_FILE", "JWT_PLATFORM_KEYS_JSON").keys());
        keys.put(TokenPopulation.INTERNAL, internal(env));
        long distinct = keys.values().stream().flatMap(List::stream).map(RSAKey::getKeyID).distinct().count();
        if (distinct != keys.values().stream().mapToInt(List::size).sum())
            throw new IllegalStateException("token populations must not share keys: a kid appears in more than one population's key set");
    }

    private static List<RSAKey> internal(Environment env) {
        String file = env.getProperty("app.jwt.internal.jwks-file", "");
        String json = env.getProperty("app.jwt.internal.jwks-json", "");
        try {
            if (!file.isBlank()) json = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("JWT_INTERNAL_JWKS_FILE is set but unreadable: " + file);
        }
        if (json.isBlank()) {
            if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod")))
                throw new IllegalStateException("Internal service-token keys are not configured: set JWT_INTERNAL_JWKS_FILE or JWT_INTERNAL_JWKS_JSON (public keys of the calling services).");
            return SigningKeyProvider.ephemeral().keys();
        }
        List<RSAKey> out = new ArrayList<>();
        try {
            for (JWK k : JWKSet.parse(json).getKeys()) {
                if (!(k instanceof RSAKey rsa) || rsa.getKeyID() == null || rsa.size() < 2048)
                    throw new IllegalStateException("internal JWKS must hold RSA keys (2048+ bits) with a kid");
                out.add(rsa);
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("internal JWKS is not a valid JWKS document");
        }
        if (out.isEmpty() || out.size() > 4) throw new IllegalStateException("internal JWKS must hold 1 to 4 keys");
        return List.copyOf(out);
    }

    /** Public keys of a population, as published and as used to verify. */
    public JWKSet publicSet(TokenPopulation p) {
        return new JWKSet(new ArrayList<JWK>(keys.get(p))).toPublicJWKSet();
    }

    /** The active signing key, or null when Core holds only public keys for this population (internal in prod). */
    RSAKey signing(TokenPopulation p) {
        RSAKey k = keys.get(p).get(0);
        return k.isPrivate() ? k : null;
    }
}
