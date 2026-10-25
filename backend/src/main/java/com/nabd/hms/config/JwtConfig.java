package com.nabd.hms.config;

import com.nabd.hms.shared.security.TokenPopulation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

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

    /** PLT-009: staff, owner, platform and internal key sets, each from its own secret. */
    @Bean
    public PopulationKeys populationKeys(Environment env, SigningKeyProvider staff) {
        return new PopulationKeys(env, staff);
    }

    @Bean
    public JwtEncoder jwtEncoder(PopulationKeys keys) {
        return JwtTokenPolicy.encoder(keys);
    }

    /** Verifies signature + issuer + header/kid with the keys of the population the token's issuer names (PLT-009). */
    @Bean
    public JwtDecoder jwtDecoder(PopulationKeys keys) {
        var byPop = new java.util.EnumMap<TokenPopulation, JwtDecoder>(TokenPopulation.class);
        for (TokenPopulation p : TokenPopulation.values()) byPop.put(p, JwtTokenPolicy.decoder(keys.publicSet(p), p.issuer()));
        return JwtTokenPolicy.dispatching(byPop);
    }
}
