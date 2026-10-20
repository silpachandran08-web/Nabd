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
 * carry "sid": staff access tokens (sid + tenantId, public.sessions) and, since PLT-007, platform-operator
 * access tokens (sid, no tenantId, master.sessions). Other token types never reach this decoder's callers.
 */
@Component
@Primary
class SessionRevocationJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final SessionLivenessChecker livenessChecker;
    private final SessionLivenessCache cache;

    SessionRevocationJwtDecoder(@Qualifier("accessTokenDecoder") JwtDecoder delegate, SessionLivenessChecker livenessChecker,
                                SessionLivenessCache cache) {
        this.delegate = delegate;
        this.livenessChecker = livenessChecker;
        this.cache = cache;
    }

    @Override
    public Jwt decode(String token) {
        Jwt jwt = delegate.decode(token);
        String sid = jwt.getClaimAsString("sid");
        String tenantId = jwt.getClaimAsString("tenantId");
        if (sid == null) {
            throw new BadJwtException("access token without sid"); // PLT-001: no pass-through for sid-less tokens
        }
        boolean active;
        try { // PLT-007: operator tokens (no tenantId) are checked against master.sessions too
            UUID s = UUID.fromString(sid);
            active = tenantId == null
                    ? livenessChecker.isActive(null, s)
                    : cache.isActive(s, jwt.getExpiresAt(), () -> livenessChecker.isActive(UUID.fromString(tenantId), s)); // OPS-020
        } catch (RuntimeException e) {
            if (com.nabd.hms.common.DbFailureAdvice.classify(e) != null) {
                // not an invalid token: SecurityConfig's entry point turns this into 503
                throw new BadJwtException("session check unavailable", e); // the resource-server filter rethrows AuthenticationServiceException, so it must be this type
            }
            throw e;
        }
        if (!active) {
            throw new BadJwtException("session revoked");
        }
        return jwt;
    }
}
