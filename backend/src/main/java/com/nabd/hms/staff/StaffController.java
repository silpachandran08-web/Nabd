package com.nabd.hms.staff;

import com.nabd.hms.auth.AuthService;
import com.nabd.hms.auth.dto.TokenPairResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.staff.dto.AcceptInviteRequest;
import com.nabd.hms.staff.dto.AcceptedStaffIdentity;
import com.nabd.hms.staff.dto.StaffInviteRequest;
import com.nabd.hms.staff.dto.StaffInviteResponse;
import com.nabd.hms.staff.dto.StaffPage;
import com.nabd.hms.staff.dto.StaffPatchRequest;
import com.nabd.hms.staff.dto.StaffResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final StaffService staffService;
    private final AuthService authService;

    StaffController(StaffService staffService, AuthService authService) {
        this.staffService = staffService;
        this.authService = authService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    public StaffPage list(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(defaultValue = "20") int limit,
                           @RequestParam(required = false) String cursor) {
        return staffService.list(tenantId(jwt), limit, cursor);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('staff:create')")
    public ResponseEntity<StaffInviteResponse> invite(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody StaffInviteRequest req) {
        StaffInviteResponse created = staffService.invite(tenantId(jwt), staffId(jwt), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:view')")
    public StaffResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return staffService.get(tenantId(jwt), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('staff:edit')")
    public StaffResponse patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @RequestBody StaffPatchRequest req) {
        return staffService.patch(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('staff:delete')")
    public ResponseEntity<Void> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        staffService.suspend(tenantId(jwt), id, staffId(jwt), stepUpToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/{token}/accept")
    public TokenPairResponse acceptInvite(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest req,
                                           HttpServletRequest http) {
        AcceptedStaffIdentity identity = staffService.acceptInvite(token, req);
        return authService.issueTokensForStaff(identity.tenantId(), identity.staffId(),
                RequestMeta.clientIp(http), RequestMeta.userAgent(http), RequestMeta.userAgent(http));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
