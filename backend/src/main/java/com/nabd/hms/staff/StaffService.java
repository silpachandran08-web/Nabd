package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.staff.dto.AcceptInviteRequest;
import com.nabd.hms.staff.dto.AcceptedStaffIdentity;
import com.nabd.hms.staff.dto.CallerInfo;
import com.nabd.hms.staff.dto.PageMeta;
import com.nabd.hms.staff.dto.StaffInviteRequest;
import com.nabd.hms.staff.dto.StaffInviteResponse;
import com.nabd.hms.staff.dto.StaffPage;
import com.nabd.hms.staff.dto.StaffPatchRequest;
import com.nabd.hms.staff.dto.StaffResponse;
import com.nabd.hms.staff.dto.StaffRosterEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final StaffRepository staffRepo;
    private final RoleRepository roleRepo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final StepUpVerifier stepUpVerifier;

    StaffService(StaffRepository staffRepo, RoleRepository roleRepo, TenantContext tenantContext,
                 PasswordEncoder pinEncoder, StepUpVerifier stepUpVerifier) {
        this.staffRepo = staffRepo;
        this.roleRepo = roleRepo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.stepUpVerifier = stepUpVerifier;
    }

    @Transactional
    public StaffPage list(UUID tenantId, int limit, String cursor) {
        tenantContext.set(tenantId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<StaffRow> rows = staffRepo.listPage(tenantId, limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<StaffRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new StaffPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    /** Powers doctor pickers in queue/consult screens — see StaffRosterEntry's doc comment for why
     * this isn't just a filtered call to list() above. */
    @Transactional
    public List<StaffRosterEntry> roster(UUID tenantId) {
        tenantContext.set(tenantId);
        return staffRepo.listRoster(tenantId);
    }

    @Transactional
    public StaffInviteResponse invite(UUID tenantId, UUID invitedBy, StaffInviteRequest req) {
        tenantContext.set(tenantId);
        if (roleRepo.findById(tenantId, req.roleId()).isEmpty()) {
            throw notFound();
        }

        UUID id = UUID.randomUUID();
        String rawToken = OpaqueTokens.generate();
        Instant expiresAt = Instant.now().plus(72, ChronoUnit.HOURS);
        try {
            staffRepo.insertInvite(id, tenantId, req.roleId(), req.email().toLowerCase(), req.name(),
                    req.mobilePhone(), req.scopeOrDefault(), OpaqueTokens.sha256Hex(rawToken), expiresAt, invitedBy);
        } catch (DataIntegrityViolationException e) {
            throw staffConflict(e);
        }

        StaffRow row = staffRepo.findById(tenantId, id).orElseThrow();
        log.info("staff {} invited by {} (tenant {}, role {})", id, invitedBy, tenantId, req.roleId());
        return new StaffInviteResponse(toResponse(row), rawToken);
    }

    @Transactional
    public StaffResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        return staffRepo.findById(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public StaffResponse patch(UUID tenantId, UUID callerStaffId, UUID id, StaffPatchRequest req) {
        tenantContext.set(tenantId);
        StaffRow current = staffRepo.findById(tenantId, id).orElseThrow(this::notFound);

        if (req.roleId() != null && roleRepo.findById(tenantId, req.roleId()).isEmpty()) {
            throw notFound();
        }

        UUID roleId = req.roleId() != null ? req.roleId() : current.roleId();
        String scope = req.scope() != null ? req.scope() : current.scope();
        List<String> fieldGrants = req.fieldGrants() != null ? req.fieldGrants() : current.fieldGrants();

        staffRepo.update(tenantId, id, roleId, scope, fieldGrants);
        log.info("staff {} updated by {} (role {}, scope {})", id, callerStaffId, roleId, scope);
        return staffRepo.findById(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public void suspend(UUID tenantId, UUID id, UUID callerStaffId, String stepUpToken) {
        stepUpVerifier.require(stepUpToken, callerStaffId);
        tenantContext.set(tenantId);
        staffRepo.findById(tenantId, id).orElseThrow(this::notFound);
        staffRepo.suspend(tenantId, id);
        staffRepo.revokeAllSessions(tenantId, id, "suspended");
        log.warn("staff {} suspended by {} (tenant {}) — all sessions killed", id, callerStaffId, tenantId);
    }

    @Transactional
    public AcceptedStaffIdentity acceptInvite(String rawToken, AcceptInviteRequest req) {
        String hash = OpaqueTokens.sha256Hex(rawToken);
        StaffRow staff = staffRepo.findByInviteTokenHash(hash).orElseThrow(this::inviteInvalid);
        tenantContext.set(staff.tenantId());
        staffRepo.acceptInvite(staff.tenantId(), staff.id(), pinEncoder.encode(req.pin()));
        log.info("staff {} accepted invite and activated (tenant {})", staff.id(), staff.tenantId());
        return new AcceptedStaffIdentity(staff.tenantId(), staff.id());
    }

    /** Cross-module read for PatientService's dual-channel verification gate (NB-041) and row-scoping (NB-051). */
    @Transactional
    public CallerInfo getCallerInfo(UUID tenantId, UUID staffId) {
        tenantContext.set(tenantId);
        StaffRow row = staffRepo.findById(tenantId, staffId).orElseThrow(this::notFound);
        return new CallerInfo(row.scope(), row.emailVerified(), row.mobileVerified(), row.fieldGrants());
    }

    private StaffResponse toResponse(StaffRow row) {
        return new StaffResponse(row.id(), row.email(), row.name(), row.mobilePhone(), row.roleId(), row.status(),
                row.scope(), row.emailVerified(), row.mobileVerified(), row.fieldGrants(), row.lastSeenAt());
    }

    private ApiException staffConflict(DataIntegrityViolationException e) {
        String detail = e.getRootCause() != null ? e.getRootCause().getMessage() : "";
        if (detail.contains("idx_staff_tenant_mobile")) {
            return new ApiException(HttpStatus.CONFLICT, "staff-mobile-conflict", "Mobile number already on this tenant",
                    "A staff member with this mobile number already exists.");
        }
        return new ApiException(HttpStatus.CONFLICT, "staff-email-conflict", "Email already on this tenant",
                "A staff member with this email already exists.");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }

    private ApiException inviteInvalid() {
        return new ApiException(HttpStatus.GONE, "invite-invalid", "Invite expired or already used",
                "This invite link is no longer valid.");
    }
}
