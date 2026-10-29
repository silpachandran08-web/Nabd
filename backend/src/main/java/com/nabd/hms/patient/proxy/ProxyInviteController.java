package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.TenantScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * IDC-009: the family member's side of the consent link (public, no session: the token is the credential and the OTP proves the phone).
 * An unknown, used or old token is the same 410. Rate limited per IP like the other public routes.
 */
@RestController
class ProxyInviteController {

    @Schema(name = "ProxyInviteAcceptRequest")
    record AcceptRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp, @NotBlank @Pattern(regexp = "BOOK_ONLY|BOOK_AND_RECORDS|FULL") String scope) {
    }

    @Schema(name = "ProxyInviteAccepted")
    record AcceptResponse(String status, String scope) {
    }

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final ProxyService service;
    private final ProxyOtpDelivery delivery;

    ProxyInviteController(JdbcTemplate jdbc, TenantScope tenantScope, ProxyService service, ProxyOtpDelivery delivery) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
        this.delivery = delivery;
    }

    @GetMapping("/v1/proxy-invites/{token}")
    ResponseEntity<ProxyService.InviteView> view(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tenantScope.run(tenantOf(hash), () -> service.view(hash)));
    }

    @PostMapping("/v1/proxy-invites/{token}/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> otp(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        ProxyService.Otp otp = tenantScope.run(tenantOf(hash), () -> service.issueOtp(hash));
        delivery.send(otp); // after the commit, with no transaction open
        return Map.of("sent", true);
    }

    @PostMapping("/v1/proxy-invites/{token}/accept")
    AcceptResponse accept(@PathVariable String token, @Valid @RequestBody AcceptRequest r, HttpServletRequest http) {
        String hash = OpaqueTokens.sha256Hex(token);
        ProxyService.Accepted a = tenantScope.run(tenantOf(hash), () -> service.accept(hash, r.otp(), r.scope(), RequestMeta.clientIp(http), http.getHeader("User-Agent")));
        return switch (a.outcome()) {
            case ACCEPTED -> new AcceptResponse("active", a.scope());
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "invite-expired", "Link expired", "This link has expired or was already used.", Map.of("code", "invite-expired"));
            case OTP_INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "otp-invalid", "Code not accepted", "That code is wrong or has expired. Ask for a new one.", Map.of("code", "otp-invalid"));
            case CONFLICT -> throw new ApiException(HttpStatus.CONFLICT, "already-accepted", "Already accepted", "This link was already used with a different choice.");
        };
    }

    /** The token names no clinic by itself: the definer function V2771 returns the invite's tenant and nothing else. */
    private UUID tenantOf(String hash) {
        UUID t = jdbc.queryForObject("SELECT proxy_invite_tenant(?)", UUID.class, hash);
        if (t == null) {
            throw new ApiException(HttpStatus.GONE, "invite-expired", "Link expired", "This link has expired or was already used.", Map.of("code", "invite-expired"));
        }
        return t;
    }
}
