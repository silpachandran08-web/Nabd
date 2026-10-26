package com.nabd.hms.auth;

import com.nabd.hms.auth.api.StepUpOtp;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@org.springframework.transaction.annotation.Transactional(noRollbackFor = ApiException.class) // joins the caller's tenant transaction; limiter and attempt counts must survive a refusal
class StepUpOtpService implements StepUpOtp {

    private static final int MAX_ATTEMPTS = 5;

    private final AuthRepository repo;
    private final OtpOutbox outbox;
    private final OtpSendLimiter limiter;
    private final TenantContext tenantContext;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    StepUpOtpService(AuthRepository repo, OtpOutbox outbox, OtpSendLimiter limiter, TenantContext tenantContext, JdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.outbox = outbox;
        this.limiter = limiter;
        this.tenantContext = tenantContext;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Challenge issue(UUID staffId, String purpose) {
        UUID tenantId = tenantContext.current();
        AuthModels.Staff staff = repo.findStaffById(staffId).orElseThrow();
        if (staff.mobilePhone() == null || staff.mobilePhone().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_MOBILE", "No mobile number", "Add a mobile number to your profile first.");
        }
        if (limiter.tryConsume(tenantId, staff.mobilePhone().replaceAll("\\D", "")) != OtpSendLimiter.Decision.SEND) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many codes", "Try again later.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expiresAt = Instant.now(clock).plus(5, ChronoUnit.MINUTES);
        repo.setWhatsAppOtp(staffId, OpaqueTokens.sha256Hex(code), expiresAt);
        return new Challenge(outbox.queue(tenantId, staffId, purpose, code, expiresAt), expiresAt);
    }

    @Override
    public boolean verify(UUID staffId, UUID challengeId, String purpose, String code) {
        // the attempt is counted first and in the same statement that checks the challenge, so a wrong code always costs one
        int open = jdbc.update("UPDATE otp_requests SET attempts = attempts + 1 WHERE id = ? AND staff_id = ? AND purpose = ? " +
                "AND expires_at > now() AND attempts < ?", challengeId, staffId, purpose, MAX_ATTEMPTS);
        return open == 1 && code != null && repo.consumeWhatsAppOtp(staffId, OpaqueTokens.sha256Hex(code));
    }
}
