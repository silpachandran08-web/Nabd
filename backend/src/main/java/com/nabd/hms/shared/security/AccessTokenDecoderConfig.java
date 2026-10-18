package com.nabd.hms.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;

/** PLT-001: the resource server's decoder. Timestamps + issuer + typ=access with a staff/platform audience. */
@Configuration
public class AccessTokenDecoderConfig {

    private static final String ISSUER = "https://api.nabd.health"; // same as AuthService.ISSUER

    @Bean
    public JwtDecoder accessTokenDecoder(KeyPair keyPair) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new JwtTokenTypeValidator(TokenType.ACCESS)));
        return d;
    }
}
