package com.nabd.hms.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

import com.nimbusds.jose.jwk.RSAKey;

import java.util.List;
import java.util.Set;

/** Decode-side rules (iss, aud, typ, kid) and an encoder that stamps typ/iss/aud defaults (PLT-002). */
public final class JwtTokenPolicy {

    public static final String ISSUER = "https://api.nabd.health";
    public static final String AUDIENCE = "nabd-api";
    /** Accepted JOSE "typ" header values; PLT-001 extends this when it introduces typed tokens. */
    public static final Set<String> TYPES = Set.of("JWT");

    private JwtTokenPolicy() {}

    public static JwtDecoder decoder(JWKSet publicKeys) {
        return decoder(publicKeys, ISSUER);
    }

    public static JwtDecoder decoder(JWKSet publicKeys, String issuer) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> selector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(publicKeys));
        processor.setJWSKeySelector(selector);
        // typ is enforced in validator() so a missing typ gives one consistent error
        processor.setJWSTypeVerifier((type, ctx) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator() {
        return validator(ISSUER);
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(new org.springframework.security.oauth2.jwt.JwtTimestampValidator(java.time.Duration.ofSeconds(5)), new org.springframework.security.oauth2.jwt.JwtIssuerValidator(issuer), JwtTokenPolicy::checkAudAndHeader);
    }

    /** PLT-009: reads iss WITHOUT trusting it, only to choose the population whose keys and issuer then verify the token. */
    public static String unverifiedIssuer(String token) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (java.text.ParseException | RuntimeException e) {
            return null;
        }
    }

    /** PLT-009: routes a token to its population's decoder by issuer; an unknown issuer is refused, never tried on another key set. */
    public static JwtDecoder dispatching(java.util.Map<com.nabd.hms.shared.security.TokenPopulation, JwtDecoder> byPopulation) {
        return token -> {
            com.nabd.hms.shared.security.TokenPopulation p = com.nabd.hms.shared.security.TokenPopulation.fromIssuer(unverifiedIssuer(token));
            if (p == null) throw new org.springframework.security.oauth2.jwt.BadJwtException("unknown issuer");
            return byPopulation.get(p).decode(token);
        };
    }

    /**
     * PLT-009: signs with the active key of the population named by the issuer claim. A token without an issuer, or with the
     * pre-PLT-009 shared one, is assigned a population from its audience and re-stamped with that population's issuer.
     * Any other issuer is signed with the staff key and kept as is (it will fail every decoder).
     */
    public static JwtEncoder encoder(PopulationKeys keys) {
        java.util.Map<com.nabd.hms.shared.security.TokenPopulation, JwtEncoder> inner = new java.util.EnumMap<>(com.nabd.hms.shared.security.TokenPopulation.class);
        for (var p : com.nabd.hms.shared.security.TokenPopulation.values()) {
            RSAKey k = keys.signing(p);
            if (k != null) inner.put(p, new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(k))));
        }
        return params -> {
            JwtClaimsSet claims = params.getClaims();
            JwtClaimsSet.Builder b = JwtClaimsSet.from(claims);
            String iss = claims.getIssuer() == null ? null : claims.getIssuer().toString();
            var p = com.nabd.hms.shared.security.TokenPopulation.fromIssuer(iss);
            if (p == null && (iss == null || iss.equals(com.nabd.hms.shared.security.TokenPopulation.LEGACY_ISSUER))) {
                p = com.nabd.hms.shared.security.TokenPopulation.forAudience(claims.getAudience());
                b.issuer(p.issuer());
            }
            if (claims.getAudience() == null) b.audience(List.of(AUDIENCE));
            JwtEncoder enc = inner.get(p == null ? com.nabd.hms.shared.security.TokenPopulation.STAFF : p);
            if (enc == null) throw new IllegalStateException("this instance holds no private key for " + p + " tokens");
            JwsHeader h = params.getJwsHeader() != null ? params.getJwsHeader() : JwsHeader.with(SignatureAlgorithm.RS256).build();
            h = JwsHeader.from(h).type("JWT").build();
            return enc.encode(JwtEncoderParameters.from(h, b.build()));
        };
    }

    static OAuth2TokenValidatorResult checkAudAndHeader(Jwt jwt) {
        // PLT-001 owns the exact per-type audience (TokenType); here we only require one to be present
        if (jwt.getAudience() == null || jwt.getAudience().isEmpty()) return fail("wrong or missing aud");
        Object typ = jwt.getHeaders().get("typ");
        if (typ == null || !TYPES.contains(typ.toString().toUpperCase(java.util.Locale.ROOT))) return fail("wrong or missing typ");
        if (jwt.getHeaders().get("kid") == null) return fail("missing kid");
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String why) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", why, null));
    }

    /** Signs with the single active key and defaults typ=JWT, iss and aud so callers need no change. */
    public static JwtEncoder encoder(com.nimbusds.jose.jwk.RSAKey active) {
        JwtEncoder inner = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(active)));
        return params -> {
            JwtClaimsSet claims = params.getClaims();
            JwtClaimsSet.Builder b = JwtClaimsSet.from(claims);
            if (claims.getIssuer() == null) b.issuer(ISSUER);
            if (claims.getAudience() == null) b.audience(List.of(AUDIENCE));
            JwsHeader h = params.getJwsHeader() != null ? params.getJwsHeader() : JwsHeader.with(SignatureAlgorithm.RS256).build();
            h = JwsHeader.from(h).type("JWT").build();
            return inner.encode(JwtEncoderParameters.from(h, b.build()));
        };
    }
}
