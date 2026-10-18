package com.nabd.hms.shared.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Accepts a token only when typ equals the expected type and it has exactly one aud that type allows. */
public class JwtTokenTypeValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenType expected;

    public JwtTokenTypeValidator(TokenType expected) {
        this.expected = expected;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        var aud = jwt.getAudience();
        if (expected.claim().equals(jwt.getClaimAsString(TokenType.CLAIM)) && aud != null && aud.size() == 1
                && expected.allows(aud.get(0))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "token type not allowed here", null));
    }
}
