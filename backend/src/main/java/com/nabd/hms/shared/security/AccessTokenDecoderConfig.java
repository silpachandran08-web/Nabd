package com.nabd.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
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
            return jwt;
        };
    }
}
