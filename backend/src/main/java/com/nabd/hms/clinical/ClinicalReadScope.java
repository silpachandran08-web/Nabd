package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.staff.StaffService;
import com.nabd.hms.staff.dto.CallerInfo;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.UUID;

/**
 * IDC-001: the read gate of every GET under /v1/clinical/** (plus dental and today's vaccine log). A controller resolves
 * the caller once, then proves the patient is visible with one of the require* probes, which run the shared
 * {@link ReadScope#gate} inside the SQL. A withdrawn, out-of-scope or unknown patient is 404 'patient-not-found'
 * (never 403, so existence does not leak). ClinicalReadScopeArchTest fails the build for a clinical GET that skips it.
 * Phase 0 bridge: IDC-027 replaces these SQL gates with the Cedar PEP.
 */
@Component
public class ClinicalReadScope {

    /** For a clinical GET that returns no patient data (drug search, rx sets, templates); the arch test accepts it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface NoPatientData {
    }

    private final JdbcTemplate jdbc;
    private final StaffService staffService;
    private final TenantContext tenantContext;

    ClinicalReadScope(JdbcTemplate jdbc, StaffService staffService, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.staffService = staffService;
        this.tenantContext = tenantContext;
    }

    /** NB-041 dual-verification gate, same refusal as /v1/patients/** (PatientService), then scope and field grants. */
    @Transactional(readOnly = true)
    public ReadScope resolve(Jwt jwt) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenantId"));
        UUID staffId = UUID.fromString(jwt.getSubject());
        CallerInfo caller = staffService.getCallerInfo(tenantId, staffId);
        if (!caller.emailVerified() || !caller.mobileVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "verification-required", "Verification required",
                    "Both email and mobile verification must be complete before patient data is reachable.");
        }
        // PLT-012 break-glass (bg claim: audited, owner-alerted, read-only, 30 min) lifts own-patients scoping only;
        // a withdrawn consent still gates. Otherwise the emergency token could not reach the stranger it exists for.
        boolean scoped = "own_patients_only".equals(caller.scope()) && !jwt.hasClaim("bg");
        return new ReadScope(tenantId, staffId, scoped ? staffId : null,
                caller.fieldGrants() == null ? List.of() : caller.fieldGrants());
    }

    @Transactional(readOnly = true)
    public void requirePatient(ReadScope scope, UUID patientId) {
        probe("SELECT p.id FROM patients p WHERE p.tenant_id = ? AND p.id = ? " + scope.gate("p.id", "p.tenant_id"), scope, patientId);
    }

    /** Gate resolved through the visit's patient (queue_entries.patient_id). */
    @Transactional(readOnly = true)
    public void requireVisit(ReadScope scope, UUID queueEntryId) {
        probe("SELECT q.id FROM queue_entries q WHERE q.tenant_id = ? AND q.id = ? " + scope.gate("q.patient_id", "q.tenant_id"), scope, queueEntryId);
    }

    @Transactional(readOnly = true)
    public void requireDocument(ReadScope scope, UUID documentId) {
        probe("SELECT d.id FROM clinical_documents d WHERE d.tenant_id = ? AND d.id = ? " + scope.gate("d.patient_id", "d.tenant_id"), scope, documentId);
    }

    private void probe(String sql, ReadScope scope, UUID id) {
        tenantContext.set(scope.tenantId());
        if (jdbc.queryForList(sql, scope.tenantId(), id).isEmpty()) {
            throw patientNotFound();
        }
    }

    /** No id, name or reason in the body: the same answer for withdrawn, out of scope and missing. */
    public static ApiException patientNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Record not available",
                "The requested patient record was not found.");
    }
}
