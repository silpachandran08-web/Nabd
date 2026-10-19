package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * CLN-005 (T-058, QA-17, hazard H-05): who may put their name on a prescription, record a requiresDecision warning
 * decision, or issue a clinical document. In order, the caller must
 * (1) be the visit's doctor or hold clinical:approve           -> else 403 not-prescriber
 * (2) be a registered clinician: staff.is_clinician AND a professional_registration_no on file
 *                                                                -> else 403 not-licensed-clinician
 * (3) hold an in-date licence (licence_expires_on null or >= today in the clinic's zone), or an owner's override
 *     that has not lapsed (licence_override_until > now)       -> else 403 licence-expired
 * (4) hold clinical:edit (a role with clinical:approve alone, e.g. the Owner preset, cannot sign) -> else not-prescriber.
 * Break-glass sessions never reach this: {@link ClinicalBreakGlassSignGuard} refuses them earlier, with an audit row.
 * Staff licence columns are read directly (no ActorDirectory yet, PLT-033); iam is their only writer.
 */
@Component
public class ClinicalAuthority {

    public static final String NOT_PRESCRIBER = "not-prescriber";
    public static final String NOT_LICENSED = "not-licensed-clinician";
    public static final String LICENCE_EXPIRED = "licence-expired";
    public static final String BREAK_GLASS = "break-glass-cannot-sign";

    /** The licence the action was taken under: snapshotted onto the signed record. */
    public record Licence(String registrationNo) {
    }

    private record StaffLicence(boolean isClinician, String registrationNo, LocalDate expiresOn, Instant overrideUntil) {
    }

    private final JdbcTemplate jdbc;
    private final ClinicClock clock;

    ClinicalAuthority(JdbcTemplate jdbc, ClinicClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Null when the caller may sign; otherwise the refusal code. Caller must have set the tenant context. */
    public String refusal(UUID tenantId, UUID visitDoctorId, UUID callerStaffId, boolean callerCanApprove) {
        if (!PrescriptionService.mayActAsVisitDoctor(visitDoctorId, callerStaffId, callerCanApprove)) {
            return NOT_PRESCRIBER;
        }
        StaffLicence l = jdbc.query("SELECT is_clinician, professional_registration_no, licence_expires_on, licence_override_until " +
                        "FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new StaffLicence(rs.getBoolean("is_clinician"), rs.getString("professional_registration_no"),
                        toDate(rs.getDate("licence_expires_on")), toInstant(rs.getTimestamp("licence_override_until"))),
                tenantId, callerStaffId).stream().findFirst().orElse(null);
        if (l == null || !l.isClinician() || l.registrationNo() == null || l.registrationNo().isBlank()) {
            return NOT_LICENSED;
        }
        boolean expired = l.expiresOn() != null && l.expiresOn().isBefore(clock.today(tenantId));
        if (expired && (l.overrideUntil() == null || !l.overrideUntil().isAfter(clock.now()))) {
            return LICENCE_EXPIRED;
        }
        if (!callerHas("clinical:edit")) {
            return NOT_PRESCRIBER;
        }
        return null;
    }

    /** Throws the matching 403 unless the caller may sign / decide / issue. {@code what} completes "can ... this". */
    public Licence require(UUID tenantId, UUID visitDoctorId, UUID callerStaffId, boolean callerCanApprove, String what) {
        String refusal = refusal(tenantId, visitDoctorId, callerStaffId, callerCanApprove);
        if (refusal != null) {
            throw forbidden(refusal, what);
        }
        return new Licence(jdbc.queryForObject("SELECT professional_registration_no FROM staff WHERE tenant_id = ? AND id = ?",
                String.class, tenantId, callerStaffId));
    }

    public static ApiException forbidden(String code, String what) {
        return switch (code) {
            case NOT_LICENSED -> new ApiException(HttpStatus.FORBIDDEN, NOT_LICENSED, "Needs a registered clinician",
                    "Only a registered clinician (marked as a clinician, with a registration number on file) can " + what + ".");
            case LICENCE_EXPIRED -> new ApiException(HttpStatus.FORBIDDEN, LICENCE_EXPIRED, "Licence expired",
                    "Your registration has expired. Renew it, or ask the clinic owner for a time-limited override, to " + what + ".");
            case BREAK_GLASS -> new ApiException(HttpStatus.FORBIDDEN, BREAK_GLASS, "Emergency access cannot sign",
                    "Emergency access is read-only and can never " + what + ". Sign in normally to do this.");
            default -> new ApiException(HttpStatus.FORBIDDEN, NOT_PRESCRIBER, "Prescriber only",
                    "Only the doctor on this visit, or a clinician with prescription approval rights, can " + what + ".");
        };
    }

    private static boolean callerHas(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    private static LocalDate toDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
