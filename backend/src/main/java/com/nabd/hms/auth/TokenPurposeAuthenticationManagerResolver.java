package com.nabd.hms.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * MF-01 / F-01 / QA-01: every JWT this app mints shares one key and issuer, so the signature alone
 * can't tell a full session from the intermediate tokens issued mid-login (mfa_pending after the PIN,
 * mfa_setup_required, owner_pending_workspace, platform_mfa_pending) or the step_up proof. Before this,
 * any of them was accepted as a bearer credential everywhere — the mfa_pending token alone could
 * activate break-glass and walk away with a full token pair, so MFA protected nothing.
 *
 * <p>The resource server now accepts a bearer token only if it is a <b>session token</b> (no
 * "purpose" claim, and a "sid" — every staff and operator access token carries one, no intermediate
 * token does), except on the few endpoints that exist to consume one specific intermediate token as
 * a bearer. Intermediate tokens that travel in a body or header (mfa_pending → /auth/mfa/verify's
 * challengeId, step_up → X-Step-Up-Token via StepUpVerifier, platform_mfa_pending) are never valid
 * bearers. Matching is on the exact servlet path, so any path variant falls through to the strict
 * session-only rule rather than the permissive one.
 */
@Component
public class TokenPurposeAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    public static final String MFA_SETUP_REQUIRED = "mfa_setup_required";
    public static final String OWNER_PENDING_WORKSPACE = "owner_pending_workspace";

    private static final Map<String, String> PURPOSE_ALLOWED_AT = Map.of(
            "/v1/auth/mfa/enroll", MFA_SETUP_REQUIRED,
            "/v1/auth/mfa/confirm", MFA_SETUP_REQUIRED,
            "/v1/owners/me/workspaces", OWNER_PENDING_WORKSPACE,
            "/v1/owners/workspaces/select", OWNER_PENDING_WORKSPACE);

    private final AuthenticationManager sessionOnly;
    private final Map<String, AuthenticationManager> byPurpose;

    TokenPurposeAuthenticationManagerResolver(JwtDecoder jwtDecoder,
                                              Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter) {
        this.sessionOnly = manager(new PurposeCheckingDecoder(jwtDecoder, Set.of()), jwtAuthenticationConverter);
        this.byPurpose = Map.of(
                MFA_SETUP_REQUIRED, manager(new PurposeCheckingDecoder(jwtDecoder, Set.of(MFA_SETUP_REQUIRED)), jwtAuthenticationConverter),
                OWNER_PENDING_WORKSPACE, manager(new PurposeCheckingDecoder(jwtDecoder, Set.of(OWNER_PENDING_WORKSPACE)), jwtAuthenticationConverter));
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        String purpose = PURPOSE_ALLOWED_AT.get(request.getServletPath());
        return purpose == null ? sessionOnly : byPurpose.get(purpose);
    }

    /** A full session token: what every endpoint not listed above requires. */
    public static boolean isSessionToken(Jwt jwt) {
        return jwt != null && jwt.getClaimAsString("purpose") == null && jwt.getClaimAsString("sid") != null;
    }

    private static AuthenticationManager manager(JwtDecoder decoder, Converter<Jwt, AbstractAuthenticationToken> converter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return new ProviderManager(provider);
    }

    private record PurposeCheckingDecoder(JwtDecoder delegate, Set<String> allowedPurposes) implements JwtDecoder {
        @Override
        public Jwt decode(String token) {
            Jwt jwt = delegate.decode(token);
            String purpose = jwt.getClaimAsString("purpose");
            boolean ok = purpose == null ? jwt.getClaimAsString("sid") != null : allowedPurposes.contains(purpose);
            if (!ok) {
                throw new BadJwtException("token not valid as a bearer credential here");
            }
            return jwt;
        }
    }
}
