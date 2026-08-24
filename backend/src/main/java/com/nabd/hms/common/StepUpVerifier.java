package com.nabd.hms.common;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Enforces x-step-up: true operations (staff suspend, patient merge, ...) — see AuthService.issueStepUpToken. */
@Component
public class StepUpVerifier {

    private final JwtDecoder jwtDecoder;

    public StepUpVerifier(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    public void require(String stepUpToken, UUID expectedStaffId) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            throw stepUpError();
        }
        Jwt token;
        try {
            token = jwtDecoder.decode(stepUpToken);
        } catch (JwtException e) {
            throw stepUpError();
        }
        boolean valid = "step_up".equals(token.getClaimAsString("purpose"))
                && expectedStaffId.toString().equals(token.getSubject());
        if (!valid) {
            throw stepUpError();
        }
    }

    private ApiException stepUpError() {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required",
                "This action requires a fresh MFA confirmation via X-Step-Up-Token.");
    }
}
