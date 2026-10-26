package com.nabd.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * PLT-001 / PLT-009: the resource servers' decoders, one per filter chain. Signature, header and kid checks come from the
 * PLT-002 {@code jwtDecoder}, which already picked the key set by issuer; each decoder here pins ONE population (issuer),
 * the matching audience and the token type, so a token of another population is a 401 on this chain whatever its claims say.
 */
@Configuration
public class AccessTokenDecoderConfig {

    /** /v1/** (clinic): staff access tokens only. */
    @Bean
    public JwtDecoder accessTokenDecoder(@Qualifier("jwtDecoder") JwtDecoder signed) {
        return pinned(signed, TokenType.ACCESS, TokenPopulation.STAFF, Audience.STAFF_API, true);
    }

    /** /v1/platform/**: operator access tokens only. */
    @Bean
    public JwtDecoder platformAccessTokenDecoder(@Qualifier("jwtDecoder") JwtDecoder signed) {
        return pinned(signed, TokenType.ACCESS, TokenPopulation.PLATFORM, Audience.PLATFORM_API, false);
    }

    /** /internal/v1/**: service tokens (aud=internal) only; they carry a service id as sub and no session. */
    @Bean
    public JwtDecoder internalTokenDecoder(@Qualifier("jwtDecoder") JwtDecoder signed) {
        return pinned(signed, TokenType.INTERNAL, TokenPopulation.INTERNAL, Audience.INTERNAL, true);
    }

    static JwtDecoder pinned(JwtDecoder signed, TokenType type, TokenPopulation population, Audience audience, boolean strict) {
        JwtTokenTypeValidator typed = new JwtTokenTypeValidator(type);
        return token -> {
            Jwt jwt = signed.decode(token);
            if (typed.validate(jwt).hasErrors()) throw new BadJwtException("token type not allowed here");
            if (jwt.getIssuer() == null || !population.issuer().equals(jwt.getIssuer().toString())) throw new BadJwtException("issuer not allowed on this path");
            if (!jwt.getAudience().contains(audience.value())) throw new BadJwtException("audience not allowed on this path");
            // a token without sub (or, for staff, tenantId) would NPE in controllers (UUID.fromString) -> 500; refuse it here as 401
            if (strict && (jwt.getSubject() == null || (population == TokenPopulation.STAFF && jwt.getClaimAsString("tenantId") == null))) throw new BadJwtException("token missing a required claim");
            return jwt;
        };
    }
}
