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
        if (stepUpToken == null || stepUpToken.isBlank()) {
            throw stepUpError();
        }
        Jwt token;
        try {
            token = jwtDecoder.decode(stepUpToken, TokenType.STEP_UP);
        } catch (JwtException e) {
            throw stepUpError();
        }
        boolean valid = "step_up".equals(token.getClaimAsString("purpose"))
                && expectedStaffId.toString().equals(token.getSubject());
        if (!valid) {
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
    }

    private ApiException stepUpError() {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                "This action requires a fresh MFA confirmation via X-Step-Up-Token.");
    }
}
