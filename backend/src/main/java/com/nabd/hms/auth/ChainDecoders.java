package com.nabd.hms.auth;

import com.nabd.hms.config.JwtTokenPolicy;
import com.nabd.hms.shared.security.TokenPopulation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * PLT-009: the decoder each SecurityFilterChain uses. The staff chain uses the primary {@link SessionRevocationJwtDecoder}.
 * The platform chain wraps the operator decoder in the same revocation check (master.sessions). The internal chain takes
 * service tokens, plus operator tokens for the few operator-driven endpoints that live under /internal/v1 (CLN-004, COM-012);
 * a staff token is never accepted there.
 */
@Configuration
class ChainDecoders {

    @Bean
    JwtDecoder platformChainDecoder(@Qualifier("platformAccessTokenDecoder") JwtDecoder platform, SessionLivenessChecker checker,
                                    SessionLivenessCache cache) {
        return new SessionRevocationJwtDecoder(platform, checker, cache);
    }

    @Bean
    JwtDecoder internalChainDecoder(@Qualifier("internalTokenDecoder") JwtDecoder service,
                                    @Qualifier("platformChainDecoder") JwtDecoder operator) {
        return token -> {
            TokenPopulation p = TokenPopulation.fromIssuer(JwtTokenPolicy.unverifiedIssuer(token));
            if (p == TokenPopulation.INTERNAL) return service.decode(token);
            if (p == TokenPopulation.PLATFORM) return operator.decode(token);
            throw new BadJwtException("issuer not allowed on this path");
        };
    }
}
