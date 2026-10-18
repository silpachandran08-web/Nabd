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
        Jwt jwt = signedOnly.decode(token);
        if (new JwtTokenTypeValidator(type).validate(jwt).hasErrors()) {
            throw new BadJwtException("token type not allowed here");
        }
        return jwt;
    }
}
