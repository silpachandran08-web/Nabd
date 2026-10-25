package com.nabd.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * PLT-001: decodes a challenge / purpose token (mfa_pending, mfa_setup, step_up, owner_select) and
 * requires the exact type + audience. Only the code that consumes that token calls this; the
 * resource server never does, so these tokens are useless as bearer credentials.
 */
@Component
public class ChallengeTokenDecoder {

    private final JwtDecoder signedOnly;

    ChallengeTokenDecoder(@Qualifier("jwtDecoder") JwtDecoder signedOnly) {
        this.signedOnly = signedOnly;
    }

    /** @throws org.springframework.security.oauth2.jwt.JwtException bad signature, expired, or wrong type/audience */
    public Jwt decode(String token, TokenType type) {
        return decode(token, type, null);
    }

    /**
     * PLT-009: also requires the token to come from {@code population}'s issuer (and so its keys). With null the population is
     * the one the token's audience implies (platform-mfa: PLATFORM, owner-select: OWNER, everything else STAFF); owner MFA
     * tokens share the "mfa" audience with staff ones, so the owner code names OWNER explicitly.
     */
    public Jwt decode(String token, TokenType type, TokenPopulation population) {
        Jwt jwt = signedOnly.decode(token);
        TokenPopulation want = population != null ? population : TokenPopulation.forAudience(jwt.getAudience());
        if (!want.issuer().equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                || new JwtTokenTypeValidator(type).validate(jwt).hasErrors()) {
            throw new BadJwtException("token type not allowed here");
        }
        return jwt;
    }
}
