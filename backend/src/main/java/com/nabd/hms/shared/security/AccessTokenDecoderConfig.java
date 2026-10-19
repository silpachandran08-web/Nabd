package com.nabd.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * PLT-001: the resource server's decoder. Signature, issuer, header and kid checks come from the
 * PLT-002 {@code jwtDecoder}; this adds typ=access with a staff/platform audience.
 */
@Configuration
public class AccessTokenDecoderConfig {

    @Bean
    public JwtDecoder accessTokenDecoder(@Qualifier("jwtDecoder") JwtDecoder signed) {
        JwtTokenTypeValidator access = new JwtTokenTypeValidator(TokenType.ACCESS);
        return token -> {
            Jwt jwt = signed.decode(token);
            if (access.validate(jwt).hasErrors()) throw new BadJwtException("token type not allowed here");
            // staff tokens open /v1/**, operator tokens open only /v1/platform/** and /internal/v1/** (CLN-004), never the reverse
            boolean platformPath = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                    && (a.getRequest().getRequestURI().startsWith("/v1/platform/") || a.getRequest().getRequestURI().startsWith("/internal/v1/"));
            Audience want = platformPath ? Audience.PLATFORM_API : Audience.STAFF_API;
            if (!jwt.getAudience().contains(want.value())) throw new BadJwtException("audience not allowed on this path");
            // a staff token without sub/tenantId would NPE in controllers (UUID.fromString) -> 500; refuse it here as 401
            if (want == Audience.STAFF_API && (jwt.getSubject() == null || jwt.getClaimAsString("tenantId") == null))
                throw new BadJwtException("token missing a required claim");
            return jwt;
        };
    }
}
