package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.WhatsAppOtpSender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * IDC-012: the young adult's claim link (public, no session: the token is the credential and the OTP to their own number proves
 * it is them; rate limited per IP by ProxyInviteSecurityConfig) and the desk's "claimed at desk" with the same OTP (patients:edit).
 * An unknown, used or old token is the same 410 claim-expired.
 */
@RestController
class ProfileClaimController {

    private static final Logger log = LoggerFactory.getLogger(ProfileClaimController.class);

    @Schema(name = "ProfileClaimRequest")
    record ClaimRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp,
                        @Pattern(regexp = "NONE|BOOK_ONLY|BOOK_AND_RECORDS") String keepGuardianScope) {
        String keep() {
            return keepGuardianScope == null ? "NONE" : keepGuardianScope;
        }
    }

    @Schema(name = "ProfileClaimed")
    record Claimed(boolean claimed, String keptScope) {
    }

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final ProfileClaimService service;
    private final WhatsAppOtpSender sender;

    ProfileClaimController(JdbcTemplate jdbc, TenantScope tenantScope, ProfileClaimService service, WhatsAppOtpSender sender) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
        this.sender = sender;
    }

    @GetMapping("/v1/me/profile-claims/{token}")
    ResponseEntity<ProfileClaimService.View> view(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tenantScope.run(tenantOf(hash), () -> service.view(hash)));
    }

    @PostMapping("/v1/me/profile-claims/{token}/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> otp(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        send(tenantScope.run(tenantOf(hash), () -> service.issueOtp(hash))); // after the commit, with no transaction open
        return Map.of("sent", true);
    }

    @PostMapping("/v1/me/profile-claims/{token}")
    Claimed claim(@PathVariable String token, @Valid @RequestBody ClaimRequest r, HttpServletRequest http) {
        String hash = OpaqueTokens.sha256Hex(token);
        return answer(tenantScope.run(tenantOf(hash), () -> service.claim(hash, r.otp(), r.keep(), RequestMeta.clientIp(http), http.getHeader("User-Agent"))), r.keep());
    }

    @PostMapping("/v1/patients/{id}/profile-claim/otp")
    @PreAuthorize("hasAuthority('patients:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> deskOtp(@PathVariable UUID id) {
        send(service.deskOtp(id));
        return Map.of("sent", true);
    }

    @PostMapping("/v1/patients/{id}/profile-claim")
    @PreAuthorize("hasAuthority('patients:edit')")
    Claimed deskClaim(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ClaimRequest r, HttpServletRequest http) {
        return answer(service.claimAtDesk(id, UUID.fromString(jwt.getSubject()), r.otp(), r.keep(), RequestMeta.clientIp(http)), r.keep());
    }

    private static Claimed answer(ProfileClaimService.Outcome o, String keep) {
        return switch (o) {
            case CLAIMED -> new Claimed(true, keep);
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "claim-expired", "Link expired", "This link has expired or was already used.", Map.of("code", "claim-expired"));
            case NOT_OPEN -> throw ProfileClaimService.notOpen();
            case OTP_INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "otp-invalid", "Code not accepted", "That code is wrong or has expired. Ask for a new one.", Map.of("code", "otp-invalid"));
            case CONFLICT -> throw new ApiException(HttpStatus.CONFLICT, "already-claimed", "Already claimed", "This profile was already claimed with a different choice.");
        };
    }

    private void send(ProfileClaimService.Otp otp) {
        try {
            sender.send(otp.phone(), otp.code());
        } catch (RuntimeException e) {
            log.warn("profile claim OTP delivery failed");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "otp-send-failed", "Code not sent", "We could not send the code. Try again in a moment.");
        }
    }

    /** The token names no clinic by itself: the definer function V3000 returns the claim's tenant and nothing else. */
    private UUID tenantOf(String hash) {
        UUID t = jdbc.queryForObject("SELECT profile_claim_tenant(?)", UUID.class, hash);
        if (t == null) {
            throw new ApiException(HttpStatus.GONE, "claim-expired", "Link expired", "This link has expired or was already used.", Map.of("code", "claim-expired"));
        }
        return t;
    }
}
