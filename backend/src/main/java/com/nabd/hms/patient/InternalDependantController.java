package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-042: a parent adds a child from the WhatsApp chat. The child is created as a minor of the household's guardian (the
 * oldest adult registered on the verified phone, the same person the household lookup lists first), with the guardian_access
 * consent row of V28 and the guardian's phone, so the household lookup finds the child at once. The Edge sends only first name,
 * date of birth and sex; the surname is the guardian's.
 */
@RestController
class InternalDependantController {

    @Schema(name = "ChatDependantRequest")
    record Request(@NotBlank @Size(max = 60) String firstName, @NotNull @Past LocalDate dob,
                   @NotNull @Pattern(regexp = "male|female|other") String sex) {
    }

    @Schema(name = "ChatDependantResponse")
    record Response(String patientRef) {
    }

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final PatientRepository repo;
    private final PatientRefs refs;
    private final ClinicClock clock;
    private final AuditService audit;
    private final EventPublisher events;

    InternalDependantController(TenantScope tenantScope, JdbcTemplate jdbc, PatientRepository repo, PatientRefs refs, ClinicClock clock,
                                AuditService audit, EventPublisher events) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.repo = repo;
        this.refs = refs;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/households/{phoneHash}/dependants")
    @ResponseStatus(HttpStatus.CREATED)
    Response add(@PathVariable UUID tid, @PathVariable String phoneHash, @Valid @RequestBody Request req) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        if (!phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).");
        }
        return tenantScope.run(tid, () -> {
            LocalDate today = clock.today(tid);
            if (Period.between(req.dob(), today).getYears() >= 18) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "not-a-minor", "Not a minor", "A dependant added from chat must be under 18.", Map.of("code", "NOT_A_MINOR"));
            }
            record Guardian(UUID id, String name, String phone) {
            }
            List<Guardian> g = jdbc.query("""
                    SELECT id, name, phone FROM patients WHERE tenant_id = ? AND status = 'active' AND guardian_id IS NULL AND dob <= ?
                      AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ? ORDER BY dob, id LIMIT 1""",
                    (rs, i) -> new Guardian(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), tid, today.minusYears(18), phoneHash);
            if (g.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No adult is registered on that phone.");
            String first = req.firstName().trim();
            List<UUID> same = jdbc.query("""
                    SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' AND guardian_id = ? AND dob = ? AND lower(split_part(name, ' ', 1)) = lower(?)""",
                    (rs, i) -> rs.getObject(1, UUID.class), tid, g.get(0).id(), req.dob(), first);
            if (!same.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "possible-duplicate", "Possible duplicate", "This child is already registered under that guardian.",
                        Map.of("code", "POSSIBLE_DUPLICATE", "patientRef", refs.refFor(same.get(0))));
            }
            String[] parts = g.get(0).name().trim().split("\\s+");
            UUID id = UUID.randomUUID();
            repo.insert(id, tid, parts.length > 1 ? first + " " + parts[parts.length - 1] : first, g.get(0).phone(), req.dob(), req.sex(), g.get(0).id(), null, null, false);
            repo.grantConsent(tid, id, "guardian_access");
            events.publish(tid, DomainEventType.CONSENT_CHANGED, id, Map.of("patientId", id, "consentType", "guardian_access", "status", "granted"));
            audit.record(tid, "system", null, "patient via edge", edge.serviceId(), null, "patient.dependant_added_via_edge", "patient", id, null,
                    Map.of("guardianId", g.get(0).id()));
            return new Response(refs.refFor(id));
        });
    }
}
