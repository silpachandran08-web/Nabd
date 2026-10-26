package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.staff.dto.InviteResendResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

/** ADM-018: the two owner actions the staff screen needs beyond invite/suspend: resend an expired invite, remind someone to enrol MFA. */
@Service
class StaffAccessService {

    private final StaffRepository staffRepo;
    private final RoleRepository roleRepo;
    private final GrantAuthority grantAuthority;
    private final AuditService audit;
    private final EventPublisher events;
    private final NotificationPort notifications;
    private final TenantContext tenantContext;
    private final ClinicClock clinicClock;
    private final Clock clock;

    StaffAccessService(StaffRepository staffRepo, RoleRepository roleRepo, GrantAuthority grantAuthority, AuditService audit,
                       EventPublisher events, NotificationPort notifications, TenantContext tenantContext, ClinicClock clinicClock, Clock clock) {
        this.staffRepo = staffRepo;
        this.roleRepo = roleRepo;
        this.grantAuthority = grantAuthority;
        this.audit = audit;
        this.events = events;
        this.notifications = notifications;
        this.tenantContext = tenantContext;
        this.clinicClock = clinicClock;
        this.clock = clock;
    }

    /** New 72 h single-use link for an invite that is still pending (expired or not); the old link stops working. */
    @Transactional
    InviteResendResponse resendInvite(UUID id, UUID callerId, String ip) {
        UUID tenantId = tenantContext.current();
        StaffRow target = staffRepo.findById(tenantId, id).orElseThrow(StaffAccessService::notFound);
        StaffRow caller = staffRepo.findById(tenantId, callerId).orElseThrow(StaffAccessService::notFound);
        grantAuthority.requireAssignable(tenantId, caller, roleRepo.findById(tenantId, target.roleId()).orElseThrow(), null, null);
        String raw = OpaqueTokens.generate();
        Instant expiresAt = Instant.now(clock).plus(72, ChronoUnit.HOURS);
        if (staffRepo.renewInvite(tenantId, id, OpaqueTokens.sha256Hex(raw), expiresAt) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "invite-not-pending", "Invite not pending", "This person has already accepted or is no longer invited.");
        }
        audit.record(tenantId, "staff", callerId, caller.name(), roleName(tenantId, caller), ip, "staff.invite_resent", "staff", id, null, Map.of("expiresAt", expiresAt.toString()));
        events.publish(tenantId, DomainEventType.STAFF_CHANGED, id, Map.of("staffId", id, "status", "invite_resent"));
        return new InviteResendResponse(id, expiresAt, raw);
    }

    /** WhatsApp/SMS nudge to a signed-up staff member whose role requires MFA and who has not enrolled; once per clinic day. */
    @Transactional
    void remindMfa(UUID id, UUID callerId, String ip) {
        UUID tenantId = tenantContext.current();
        StaffRow target = staffRepo.findById(tenantId, id).orElseThrow(StaffAccessService::notFound);
        if (!"active".equals(target.status()) || !target.roleMfaRequired() || target.mfaEnabled() || target.ownerLinked()) {
            throw new ApiException(HttpStatus.CONFLICT, "mfa-not-pending", "No enrolment pending", "This person does not need to enrol an authenticator.");
        }
        if (target.mobilePhone() == null || target.mobilePhone().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "no-mobile", "No mobile number", "Add a mobile number before sending a reminder.");
        }
        // 422 TEMPLATE_NOT_SENDABLE (thrown here, in this transaction) when the clinic has no approved mfa_enrol_reminder template.
        notifications.request(new NotificationRequest(NotificationPurpose.MFA_ENROL_REMINDER, "staff", id, null, target.mobilePhone(), "en", List.of(),
                null, null, null, "mfa-remind:" + id + ":" + clinicClock.today(tenantId)));
        StaffRow caller = staffRepo.findById(tenantId, callerId).orElseThrow(StaffAccessService::notFound);
        audit.record(tenantId, "staff", callerId, caller.name(), roleName(tenantId, caller), ip, "staff.mfa_reminder_sent", "staff", id, null, Map.of());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    String roleName(UUID tenantId, StaffRow s) {
        return roleRepo.findById(tenantId, s.roleId()).map(r -> r.name()).orElse("Staff");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
