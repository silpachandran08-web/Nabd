package com.nabd.hms.common;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import com.nabd.hms.shared.security.ChallengeTokenDecoder;
import com.nabd.hms.shared.security.TokenType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Enforces x-step-up: true operations (staff suspend, patient merge, ...) — see AuthService.issueStepUpToken. */
@Component
public class StepUpVerifier {

    private final ChallengeTokenDecoder jwtDecoder;

    public StepUpVerifier(ChallengeTokenDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    public void require(String stepUpToken, UUID expectedStaffId) {
        decode(stepUpToken, "step_up", expectedStaffId);
    }

    /** IDC-013: a step-up token's one-time id (jti) and the object it names (claim "target", asked for at /v1/auth/mfa/verify). */
    public record StepUp(UUID assertionId, String target) {
    }

    /** IDC-013: like {@link #require} but the token must name {@code target} and carry a jti, which the caller stores once
     * (unique column) so a replay fails; the older gates (staff suspend, merge, ABDM) keep {@link #require}. */
    public StepUp requireFor(String stepUpToken, UUID expectedStaffId, String target) {
        Jwt t = decode(stepUpToken, "step_up", expectedStaffId);
        if (t.getId() == null || target == null || !target.equals(t.getClaimAsString("target"))) {
            throw stepUpError();
        }
        try {
            return new StepUp(UUID.fromString(t.getId()), target);
        } catch (IllegalArgumentException e) {
            throw stepUpError();
        }
    }

    /** FIN-018: who the PIN assertion names, its one-time id (jti) and the approval it was asked for (null = any one approval). */
    public record PinAssertion(UUID ownerId, UUID assertionId, UUID approvalId) {
    }

    /** FIN-018: an owner's PIN confirmed at someone else's desk (AuthService.issuePinAssertion). Bound to the caller's session
     * and tenant like any step-up token; the caller must also consume assertionId once. */
    public PinAssertion requirePinAssertion(String assertion) {
        Jwt t = decode(assertion, "pin_step_up", null);
        if (t.getId() == null) {
            throw stepUpError();
        }
        String approval = t.getClaimAsString("approvalId");
        try {
            return new PinAssertion(UUID.fromString(t.getSubject()), UUID.fromString(t.getId()), approval == null ? null : UUID.fromString(approval));
        } catch (IllegalArgumentException e) {
            throw stepUpError();
        }
    }

    private Jwt decode(String stepUpToken, String purpose, UUID expectedStaffId) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            throw stepUpError();
        }
        Jwt token;
        try {
            token = jwtDecoder.decode(stepUpToken, TokenType.STEP_UP);
        } catch (JwtException e) {
            throw stepUpError();
        }
        if (!purpose.equals(token.getClaimAsString("purpose")) || token.getSubject() == null
                || (expectedStaffId != null && !expectedStaffId.toString().equals(token.getSubject()))) {
            throw stepUpError();
        }
        // PLT-007: a step-up token only works with the access token of the session (and tenant) that minted it
        if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() instanceof
                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken caller) {
            String sid = token.getClaimAsString("sid");
            String tenantId = token.getClaimAsString("tenantId");
            if (sid == null || !sid.equals(caller.getToken().getClaimAsString("sid"))
                    || (tenantId != null && !tenantId.equals(caller.getToken().getClaimAsString("tenantId")))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_INVALID", "Step-up token not valid here",
                        "This step-up confirmation belongs to another session; confirm again.");
            }
        }
        return token;
    }

    private ApiException stepUpError() {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                "This action requires a fresh MFA confirmation via X-Step-Up-Token.", java.util.Map.of("code", "STEP_UP_REQUIRED"));
    }
}
