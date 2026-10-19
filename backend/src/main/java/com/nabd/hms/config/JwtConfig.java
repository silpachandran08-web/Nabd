package com.nabd.hms.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.ArrayList;

/**
 * PLT-002: keys come from a {@link SigningKeyProvider} (secret mount / env, ephemeral only outside
 * prod), so restarts and replicas share them. See docs/runbooks/jwt-key-rotation.md.
 */
@Configuration
public class JwtConfig {

    @Bean
    public SigningKeyProvider signingKeyProvider(Environment env) {
        return SigningKeyProvider.from(env);
    }

    @Bean
    public JwtEncoder jwtEncoder(SigningKeyProvider keys) {
        return JwtTokenPolicy.encoder(keys.keys().get(0));
    }

    @Bean
    public JwtDecoder jwtDecoder(SigningKeyProvider keys) {
        return JwtTokenPolicy.decoder(new JWKSet(new ArrayList<JWK>(keys.keys())).toPublicJWKSet());
    }
}
