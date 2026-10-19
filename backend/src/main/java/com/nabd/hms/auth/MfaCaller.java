package com.nabd.hms.auth;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/** PLT-001: who is calling MFA enrol/confirm: a typ=mfa_setup token from the body, else the access session. */
@Component
class MfaCaller {

    private final ChallengeTokenDecoder decoder;

    MfaCaller(ChallengeTokenDecoder decoder) {
        this.decoder = decoder;
    }

    Jwt resolve(Jwt accessSession, String setupToken) {
        if (setupToken != null && !setupToken.isBlank()) {
            try {
                Jwt setup = decoder.decode(setupToken, TokenType.MFA_SETUP);
                if (!"mfa_setup_required".equals(setup.getClaimAsString("purpose"))) {
                    throw notAllowed(); // PLT-005: an owner's or operator's setup token never enrols a staff member
                }
                return setup;
            } catch (JwtException e) {
                throw notAllowed();
            }
        }
        if (accessSession == null) {
            throw notAllowed();
        }
        return accessSession;
    }

    private ApiException notAllowed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "token-type-not-allowed", "Token type not allowed",
                "This action needs an MFA setup token or a signed-in session.");
    }
}
