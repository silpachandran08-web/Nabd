package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.PinChangeRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PLT-003: set a 6-8 digit PIN (forced after the policy upgrade). Current PIN required; wrong ones count toward the lockout. */
@RestController
@RequestMapping("/v1/auth/pin")
public class PinChangeController {

    private final AuthRepository repo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final LoginGuard guard;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    PinChangeController(AuthRepository repo, TenantContext tenantContext, PasswordEncoder pinEncoder,
                        LoginGuard guard, JdbcTemplate jdbc, AuditService audit) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.guard = guard;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PostMapping("/change")
    @Transactional(noRollbackFor = ApiException.class) // the failed-attempt row must survive the 401
    public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PinChangeRequest req) {
        UUID staffId = UUID.fromString(jwt.getSubject());
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenantId"));
        tenantContext.set(tenantId);
        AuthModels.Staff staff = repo.findStaffById(staffId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such account."));
        guard.enforceLockout(staffId);
        if (staff.pinHash() == null || !pinEncoder.matches(req.currentPin(), staff.pinHash())) {
            guard.recordAttempt(tenantId, staffId, staff.email(), "pin", false);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                    "Email/mobile or credential is incorrect.");
        }
        jdbc.update("UPDATE staff SET pin_hash = ?, pin_policy_version = 2 WHERE id = ?", pinEncoder.encode(req.newPin()), staffId);
        audit.record(tenantId, "staff", staffId, staff.name(), "", null, "auth.pin_changed", "staff", staffId, null, null);
        return ResponseEntity.noContent().build();
    }
}
