package com.nabd.hms.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * NB-043: "revoked session denied within 60s" — a bare stateless JwtDecoder can't do this, since a
 * revoked session's already-issued access token stays cryptographically valid until its own
 * (15-minute) expiry. Wraps the real decoder with one extra check, applied only to tokens that
 * carry BOTH "sid" and "tenantId" — i.e. staff access tokens minted by AuthService.mintTokenPair.
 * Every other token this app issues (MFA challenge, step-up, mfa_setup_required, platform-operator
 * tokens) is missing one or the other claim and passes through unchanged; see mintTokenPair's
 * neighbors for why none of them share this exact claim pair.
 */
@Component
@Primary
class SessionRevocationJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final SessionLivenessChecker livenessChecker;

    SessionRevocationJwtDecoder(@Qualifier("jwtDecoder") JwtDecoder delegate, SessionLivenessChecker livenessChecker) {
        this.delegate = delegate;
        this.livenessChecker = livenessChecker;
    }

    @Override
    public Jwt decode(String token) {
        Jwt jwt = delegate.decode(token);
        String sid = jwt.getClaimAsString("sid");
        String tenantId = jwt.getClaimAsString("tenantId");
        if (sid != null && tenantId != null && !livenessChecker.isActive(UUID.fromString(tenantId), UUID.fromString(sid))) {
            throw new BadJwtException("session revoked");
        }
        return jwt;
    }
}
