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
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> selector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(publicKeys));
        processor.setJWSKeySelector(selector);
        // typ is enforced in validator() so a missing typ gives one consistent error
        processor.setJWSTypeVerifier((type, ctx) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator());
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator() {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), JwtTokenPolicy::checkAudAndHeader);
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
