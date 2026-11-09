package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.staff.StaffLicenceController.LicenceRequest;
import static com.nabd.hms.staff.StaffLicenceController.LicenceResponse;
import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

/** CLN-005: marks a staff member as a registered clinician (needs a registration number on file), records the licence
 * expiry, and lets the Owner grant an expired licence a time-boxed signing override. Every change is audited; the
 * registration number itself never goes into the audit row or the log. */
@Service
class StaffLicenceService {

    // shortcut: fixed 30-day ceiling on an expired-licence override (mirrors MAX_DELEGATION_WINDOW); needs
    // compliance sign-off, make it a clinic policy if regulators require a different window.
    static final Duration MAX_OVERRIDE = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final StaffRepository staffRepo;
    private final RoleRepository roleRepo;
    private final AuditService auditService;
    private final TenantContext tenantContext;
    private final java.time.Clock clock;

    StaffLicenceService(JdbcTemplate jdbc, StaffRepository staffRepo, RoleRepository roleRepo, AuditService auditService,
                        TenantContext tenantContext, java.time.Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.staffRepo = staffRepo;
        this.roleRepo = roleRepo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public LicenceResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        return read(tenantId, id);
    }

    @Transactional
    public LicenceResponse update(UUID tenantId, UUID callerId, UUID id, LicenceRequest req, String ip) {
        tenantContext.set(tenantId);
        LicenceResponse before = read(tenantId, id);
        if (req.isClinician() && !before.hasRegistrationNo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "registration-no-required", "Registration number required",
                    "Add this person's professional registration number before marking them as a clinician who can sign.");
        }
        staffRepo.updateClinician(tenantId, id, req.isClinician()); // PLT-016's single is_clinician writer (also resolves the review row)
        jdbc.update("UPDATE staff SET licence_expires_on = ? WHERE tenant_id = ? AND id = ?",
                req.licenceExpiresOn() == null ? null : java.sql.Date.valueOf(req.licenceExpiresOn()), tenantId, id);
        LicenceResponse after = read(tenantId, id);
        audit(tenantId, callerId, ip, "staff.clinical_licence_updated", id, snapshot(before), snapshot(after));
        return after;
    }

    @Transactional
    public LicenceResponse grantOverride(UUID tenantId, UUID callerId, UUID id, Instant until, String ip) {
        tenantContext.set(tenantId);
        StaffRow caller = staffRepo.findById(tenantId, callerId).orElseThrow(ApiException::notFound);
        if (!GrantAuthority.isOwner(roleRepo.findById(tenantId, caller.roleId()).orElseThrow(ApiException::notFound))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "Owner only",
                    "Only the clinic owner can let an expired licence keep signing.");
        }
        LicenceResponse before = read(tenantId, id);
        Instant now = Instant.now(clock);
        if (!until.isAfter(now) || until.isAfter(now.plus(MAX_OVERRIDE))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-override-window", "Invalid override window",
                    "An override must end in the future and within " + MAX_OVERRIDE.toDays() + " days.");
        }
        jdbc.update("UPDATE staff SET licence_override_until = ? WHERE tenant_id = ? AND id = ?",
                java.sql.Timestamp.from(until), tenantId, id);
        LicenceResponse after = read(tenantId, id);
        audit(tenantId, callerId, ip, "staff.licence_override_granted", id, snapshot(before), snapshot(after));
        return after;
    }

    private LicenceResponse read(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, is_clinician, professional_registration_no, licence_expires_on, licence_override_until " +
                        "FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new LicenceResponse(UUID.fromString(rs.getString("id")), rs.getBoolean("is_clinician"),
                        rs.getString("professional_registration_no") != null && !rs.getString("professional_registration_no").isBlank(),
                        rs.getDate("licence_expires_on") == null ? null : rs.getDate("licence_expires_on").toLocalDate(),
                        rs.getTimestamp("licence_override_until") == null ? null : rs.getTimestamp("licence_override_until").toInstant()),
                tenantId, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private static Map<String, Object> snapshot(LicenceResponse l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isClinician", l.isClinician());
        m.put("licenceExpiresOn", l.licenceExpiresOn() == null ? null : l.licenceExpiresOn().toString());
        m.put("licenceOverrideUntil", l.licenceOverrideUntil() == null ? null : l.licenceOverrideUntil().toString());
        return m;
    }

    private void audit(UUID tenantId, UUID callerId, String ip, String action, UUID id, Object before, Object after) {
        StaffRow caller = staffRepo.findById(tenantId, callerId).orElseThrow(ApiException::notFound);
        String role = roleRepo.findById(tenantId, caller.roleId()).map(StaffRoleModels.RoleRow::name).orElse("Staff");
        auditService.record(tenantId, "staff", callerId, caller.name(), role, ip, action, "staff", id, before, after);
    }

}
