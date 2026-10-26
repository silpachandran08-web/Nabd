package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.SessionRevocation;
import com.nabd.hms.staff.dto.AcceptInviteRequest;
import com.nabd.hms.staff.dto.AcceptedStaffIdentity;
import com.nabd.hms.staff.dto.CallerInfo;
import com.nabd.hms.staff.dto.MfaResetRequest;
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

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.RoleRow;
import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final StaffRepository staffRepo;
    private final RoleRepository roleRepo;
    private final TenantContext tenantContext;
    private final PasswordEncoder pinEncoder;
    private final StepUpVerifier stepUpVerifier;
    private final AuditService auditService;
    private final GrantAuthority grantAuthority;
    private final EventPublisher eventPublisher;

    private final Clock clock;
    private final com.nabd.hms.common.ClinicClock clinicClock;
    private final com.nabd.hms.organization.api.Entitlements entitlements;

    StaffService(StaffRepository staffRepo, RoleRepository roleRepo, TenantContext tenantContext,
                 PasswordEncoder pinEncoder, StepUpVerifier stepUpVerifier, AuditService auditService,
                 GrantAuthority grantAuthority, EventPublisher eventPublisher, Clock clock,
                 com.nabd.hms.common.ClinicClock clinicClock, com.nabd.hms.organization.api.Entitlements entitlements) {
        this.entitlements = entitlements;
        this.clock = clock;
        this.clinicClock = clinicClock;
        this.staffRepo = staffRepo;
        this.roleRepo = roleRepo;
        this.tenantContext = tenantContext;
        this.pinEncoder = pinEncoder;
        this.stepUpVerifier = stepUpVerifier;
        this.auditService = auditService;
        this.grantAuthority = grantAuthority;
        this.eventPublisher = eventPublisher;
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
        // invitedBy == null is the provisioning bootstrap (system issues the first Owner invite); no caller to bound
        if (invitedBy != null) {
            StaffRow caller = staffRepo.findById(tenantId, invitedBy).orElseThrow(this::notFound);
            grantAuthority.requireAssignable(tenantId, caller, roleRepo.findById(tenantId, req.roleId()).orElseThrow(this::notFound),
                    req.scopeOrDefault(), null);
            // PLT-059: invited + active staff fill seats, suspended do not. Pending invites count, so accepting one (activation) never exceeds the limit.
            if (staffRepo.countSeats(tenantId) >= entitlements.seatLimit()) {
                throw new ApiException(HttpStatus.CONFLICT, "seat-limit-reached", "SEAT_LIMIT_REACHED",
                        "Your plan's staff seats are all in use. Suspend someone or upgrade your plan.", Map.of("upgradeUrl", "/owner/plan"));
            }
        }

        String mobile = com.nabd.hms.common.PhoneNumbers.require(req.mobilePhone(), java.util.Objects.requireNonNullElse(clinicClock.region(tenantId), "IN"), "mobilePhone"); // ACC-028
        UUID id = UUID.randomUUID();
        String rawToken = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(72, ChronoUnit.HOURS);
        try {
            staffRepo.insertInvite(id, tenantId, req.roleId(), req.email().toLowerCase(), req.name(),
                    mobile, req.scopeOrDefault(), OpaqueTokens.sha256Hex(rawToken), expiresAt, invitedBy, req.departmentId());
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
    public StaffResponse patch(UUID tenantId, UUID callerStaffId, UUID id, StaffPatchRequest req, String ipAddress) {
        tenantContext.set(tenantId);
        StaffRow current = staffRepo.findById(tenantId, id).orElseThrow(this::notFound);

        StaffRow caller = staffRepo.findById(tenantId, callerStaffId).orElseThrow(this::notFound);
        RoleRow newRole = req.roleId() == null ? null : roleRepo.findById(tenantId, req.roleId()).orElseThrow(this::notFound);
        boolean roleChanged = newRole != null && !newRole.id().equals(current.roleId());
        boolean scopeChanged = req.scope() != null && !req.scope().equals(current.scope());
        boolean fieldsChanged = req.fieldGrants() != null
                && !new java.util.HashSet<>(req.fieldGrants()).equals(new java.util.HashSet<>(current.fieldGrants()));
        if (roleChanged) { // moving someone off the Owner role needs Owner too; then the new role must fit the caller
            grantAuthority.requireAssignable(tenantId, caller, roleRepo.findById(tenantId, current.roleId()).orElseThrow(), null, null);
            grantAuthority.requireAssignable(tenantId, caller, newRole, null, null);
        }
        grantAuthority.requireAssignable(tenantId, caller, null, scopeChanged ? req.scope() : null, fieldsChanged ? req.fieldGrants() : null);

        UUID roleId = req.roleId() != null ? req.roleId() : current.roleId();
        String scope = req.scope() != null ? req.scope() : current.scope();
        List<String> fieldGrants = req.fieldGrants() != null ? req.fieldGrants() : current.fieldGrants();
        UUID departmentId = req.departmentId() != null ? req.departmentId() : current.departmentId();
        String registrationAfter = req.professionalRegistrationNo() == null ? current.professionalRegistrationNo()
                : req.professionalRegistrationNo().trim().isEmpty() ? null : req.professionalRegistrationNo().trim();
        // PLT-016: a clinician is set when a registration is recorded; an explicit toggle wins, but "on" needs a number.
        boolean registrationSet = registrationAfter != null && !registrationAfter.equals(current.professionalRegistrationNo());
        boolean clinicianAfter = req.clinician() != null ? req.clinician() : registrationSet || current.clinician();
        if (clinicianAfter && !current.clinician() && registrationAfter == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clinician-needs-registration", "Registration number required",
                    "Record a professional registration number before marking this person as a clinician.");
        }

        staffRepo.update(tenantId, id, roleId, scope, fieldGrants, departmentId);
        log.info("staff {} updated by {} (role {}, scope {})", id, callerStaffId, roleId, scope);
        staffChanged(tenantId, id, "updated");

        if (roleChanged || scopeChanged || fieldsChanged) {
            auditRoleChange(tenantId, caller, id, current, roleId, scope, fieldGrants, scopeChanged, ipAddress);
        }

        // DOC-N1: the registration number is printed on prescriptions, so a change is audited. The audit row records
        // that it was set / changed / cleared, not the number itself, and the number is never written to the log.
        if (req.professionalRegistrationNo() != null) {
            String next = req.professionalRegistrationNo().trim();
            String normalised = next.isEmpty() ? null : next;
            String previous = current.professionalRegistrationNo();
            if (!java.util.Objects.equals(previous, normalised)) {
                staffRepo.updateProfessionalRegistrationNo(tenantId, id, normalised);
                String change = previous == null ? "set" : normalised == null ? "cleared" : "changed";
                String callerRole = roleRepo.findById(tenantId, caller.roleId()).map(r -> r.name()).orElse("Staff");
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("field", "professionalRegistrationNo");
                detail.put("change", change);
                auditService.record(tenantId, "staff", callerStaffId, caller.name(), callerRole,
                        ipAddress, "staff.registration_no_" + change, "staff", id, null, detail);
            }
        }
        if (clinicianAfter != current.clinician() || req.clinician() != null) {
            staffRepo.updateClinician(tenantId, id, clinicianAfter); // an explicit confirm also clears the backfill review row
        }
        if (clinicianAfter != current.clinician()) {
            String callerRole = roleRepo.findById(tenantId, caller.roleId()).map(r -> r.name()).orElse("Staff");
            auditService.record(tenantId, "staff", callerStaffId, caller.name(), callerRole, ipAddress,
                    clinicianAfter ? "staff.clinician_set" : "staff.clinician_cleared", "staff", id,
                    Map.of("clinician", current.clinician()), Map.of("clinician", clinicianAfter));
        }
        return staffRepo.findById(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    /** PLT-016: the clinician backfill's ambiguous rows still waiting for the owner. */
    @Transactional
    public List<com.nabd.hms.staff.dto.ClinicianReviewEntry> clinicianReview(UUID tenantId) {
        tenantContext.set(tenantId);
        return staffRepo.listClinicianReview(tenantId);
    }

    /** PLT-015: before/after audit, sessions killed so the new grants apply on the next request, event published. */
    private void auditRoleChange(UUID tenantId, StaffRow caller, UUID id, StaffRow before, UUID roleId, String scope,
                                 List<String> fieldGrants, boolean scopeChanged, String ipAddress) {
        String callerRole = roleRepo.findById(tenantId, caller.roleId()).map(r -> r.name()).orElse("Staff");
        auditService.record(tenantId, "staff", caller.id(), caller.name(), callerRole, ipAddress, "staff.role_changed", "staff", id,
                Map.of("roleId", before.roleId(), "scope", before.scope(), "fieldGrants", before.fieldGrants()),
                Map.of("roleId", roleId, "scope", scope, "fieldGrants", fieldGrants));
        revokeSessions(tenantId, id, "role_changed"); // PLT-007: the next sign-in carries the new grants
        eventPublisher.publish(tenantId, StaffRoleChangedHandler.TYPE, Map.of("tenantId", tenantId.toString(),
                "staffId", id.toString(), "fromRoleId", before.roleId().toString(), "toRoleId", roleId.toString(),
                "scopeChanged", scopeChanged));
    }

    /** PLT-007 + OPS-015: revoke every live session of one staff member in this transaction; the event lets caches and open tabs drop them. */
    private int revokeSessions(UUID tenantId, UUID staffId, String reason) {
        List<UUID> ids = staffRepo.revokeAllSessions(tenantId, staffId, reason);
        if (!ids.isEmpty()) {
            eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.SESSION_REVOKED, staffId,
                    Map.of("staffId", staffId, "scope", "all_sessions", "reasonCode", reason, "status", "revoked"));
        }
        return ids.size();
    }

    private void staffChanged(UUID tenantId, UUID staffId, String status) {
        eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.STAFF_CHANGED, staffId,
                Map.of("staffId", staffId, "status", status));
    }

    @Transactional
    public void suspend(UUID tenantId, UUID id, UUID callerStaffId, String stepUpToken) {
        stepUpVerifier.require(stepUpToken, callerStaffId);
        tenantContext.set(tenantId);
        staffRepo.findById(tenantId, id).orElseThrow(this::notFound);
        staffRepo.suspend(tenantId, id);
        int revoked = revokeSessions(tenantId, id, "suspended"); // NB-047: every device
        staffChanged(tenantId, id, "suspended");
        StaffRow caller = staffRepo.findById(tenantId, callerStaffId).orElse(null);
        auditService.record(tenantId, "staff", callerStaffId, caller == null ? "Staff" : caller.name(),
                caller == null ? "Staff" : roleRepo.findById(tenantId, caller.roleId()).map(r -> r.name()).orElse("Staff"),
                null, "staff.suspended", "staff", id, null, Map.of("sessionsRevoked", revoked));
        log.warn("staff {} suspended by {} (tenant {}) — all sessions killed", id, callerStaffId, tenantId);
    }

    /** PLT-004: idempotent; the audit row is written on every call. */
    @Transactional
    public void resetMfa(UUID tenantId, UUID id, UUID callerStaffId, String stepUpToken, MfaResetRequest req, String ipAddress) {
        stepUpVerifier.require(stepUpToken, callerStaffId);
        tenantContext.set(tenantId);
        staffRepo.findById(tenantId, id).orElseThrow(this::notFound);
        staffRepo.resetMfa(tenantId, id);
        revokeSessions(tenantId, id, "admin_revoke");
        StaffRow caller = staffRepo.findById(tenantId, callerStaffId).orElse(null);
        String callerRole = caller == null ? "Staff" : roleRepo.findById(tenantId, caller.roleId()).map(r -> r.name()).orElse("Staff");
        auditService.record(tenantId, "staff", callerStaffId, caller == null ? "Staff" : caller.name(), callerRole,
                ipAddress, "staff.mfa_reset", "staff", id, null, Map.of("reasonCode", req.reasonCode()));
        log.warn("MFA of staff {} reset by {} (tenant {}), sessions revoked", id, callerStaffId, tenantId);
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
                row.scope(), row.emailVerified(), row.mobileVerified(), row.fieldGrants(), row.departmentId(), row.lastSeenAt(),
                row.professionalRegistrationNo(), loginMethod(row), loginFactors(row), row.clinician());
    }

    /**
     * OWN-034: how this account signs in, derived only from what the schema stores (AuthService's
     * real login paths). Which first factor a person actually used is NOT recorded anywhere
     * (sessions/login_attempts hold no method), so when both PIN and WhatsApp OTP are available
     * this says so rather than picking one. Status (suspended) is reported separately.
     */
    static String loginMethod(StaffRow row) {
        if (row.ownerLinked()) {
            return "owner_account";              // shadow row: signs in via /owners/auth/login (owner email + PIN)
        }
        if ("invited".equals(row.status())) {
            return "invite_pending";             // no credential until the invite is accepted
        }
        if (row.mfaEnabled()) {
            return "authenticator";              // every sign-in ends with a TOTP code
        }
        if (row.roleMfaRequired()) {
            return "authenticator_setup_required"; // next sign-in forces authenticator enrolment
        }
        boolean otp = row.mobilePhone() != null;
        if (row.hasPin() && otp) {
            return "pin_or_whatsapp_otp";
        }
        if (row.hasPin()) {
            return "pin";
        }
        return otp ? "whatsapp_otp" : "none";
    }

    /** OWN-034: the factors this staff row can currently use (PIN, WhatsApp OTP to the mobile on file, authenticator). */
    static List<String> loginFactors(StaffRow row) {
        if (row.ownerLinked() || "invited".equals(row.status())) {
            return List.of();
        }
        List<String> factors = new java.util.ArrayList<>();
        if (row.hasPin()) {
            factors.add("pin");
        }
        if (row.mobilePhone() != null) {
            factors.add("whatsapp_otp");
        }
        if (row.mfaEnabled()) {
            factors.add("authenticator");
        }
        return List.copyOf(factors);
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
