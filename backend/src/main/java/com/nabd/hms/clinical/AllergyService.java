package com.nabd.hms.clinical;

import com.nabd.hms.iam.api.ActorDirectory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.clinical.dto.AllergyCorrectionRequest;
import com.nabd.hms.clinical.dto.AllergyEventResponse;
import com.nabd.hms.clinical.dto.AllergyRemovalRequest;
import com.nabd.hms.clinical.dto.AllergyResponse;
import com.nabd.hms.clinical.dto.AllergyStatusResponse;
import com.nabd.hms.clinical.dto.AllergyWriteRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyEventRow;
import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.AllergyModels.AssessmentRow;

/**
 * NB-107/108 register + hard-warning, DOC-046 corrections. Severity/reaction are free text against a
 * fixed vocabulary (mild/moderate/severe), not a coded allergen list: this app has no such reference data.
 *
 * A register entry is never silently overwritten or deleted. A correction or removal needs a reason, keeps
 * the original values in patient_allergy_events (append-only, enforced by trigger) and writes an audit row.
 */
@Service
public class AllergyService {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final AllergyRepository repo;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final com.nabd.hms.common.EventPublisher events;

    private final ActorDirectory actors;
    private final DrugCoding coding;

    AllergyService(AllergyRepository repo, TenantContext tenantContext, AuditService auditService, ObjectMapper objectMapper,
                   com.nabd.hms.common.EventPublisher events, ActorDirectory actors, DrugCoding coding) {
        this.coding = coding;
        this.events = events;
        this.actors = actors;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<AllergyResponse> list(UUID tenantId, UUID patientId, boolean includeInactive) {
        tenantContext.set(tenantId);
        List<AllergyRow> rows = includeInactive ? repo.findAllByPatient(tenantId, patientId) : repo.findActiveByPatient(tenantId, patientId);
        Set<UUID> staff = new HashSet<>();
        rows.forEach(r -> {
            staff.add(r.recordedBy());
            if (r.deactivatedBy() != null) {
                staff.add(r.deactivatedBy());
            }
        });
        Map<UUID, String> names = repo.findStaffNames(tenantId, staff);
        return rows.stream().map(a -> toResponse(a, names.get(a.recordedBy()), a.deactivatedBy() == null ? null : names.get(a.deactivatedBy()))).toList();
    }

    @Transactional
    public AllergyResponse add(UUID tenantId, UUID patientId, UUID callerStaffId, String ipAddress, AllergyWriteRequest req) {
        tenantContext.set(tenantId);
        if (!repo.patientExists(tenantId, patientId)) {
            throw ApiException.notFound();
        }
        String substance = req.substance().trim();
        if (substance.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Substance is required.");
        }
        repo.lockRegister(tenantId, patientId);
        boolean duplicate = repo.findActiveByPatient(tenantId, patientId).stream()
                .anyMatch(a -> a.substance().trim().equalsIgnoreCase(substance));
        if (duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "allergy-exists", "Allergy already recorded",
                    "'" + substance + "' is already on this patient's allergy register. Correct the existing entry instead.");
        }
        String source = req.source() == null ? "clinician" : req.source();
        String ingredientId = blankToNull(req.allergenIngredientId());
        String classId = blankToNull(req.allergenClassId());
        coding.requireAllergen(ingredientId, classId);
        UUID id = repo.insert(tenantId, patientId, substance, req.category(), req.severity(), blankToNull(req.reaction()), source, callerStaffId,
                ingredientId, classId);
        AllergyRow row = repo.findById(tenantId, id).orElseThrow();
        // A real allergy supersedes an earlier "no known allergies" statement; it is never left standing next to one.
        if (repo.deleteAssessment(tenantId, patientId) > 0) {
            audit(tenantId, callerStaffId, ipAddress, "allergy.no_known_allergies_superseded", patientId, null, Map.of("allergyId", id.toString()));
        }
        Map<String, Object> after = snapshot(row);
        repo.insertEvent(tenantId, patientId, id, "recorded", null, json(after), null, callerStaffId);
        allergyChanged(tenantId, patientId, Map.of("allergyId", id, "change", "recorded", "status", "active"));
        audit(tenantId, callerStaffId, ipAddress, "allergy.recorded", id, null, after);
        return toResponse(row, repo.findStaffNames(tenantId, Set.of(callerStaffId)).get(callerStaffId), null);
    }

    @Transactional
    public AllergyResponse correct(UUID tenantId, UUID id, UUID callerStaffId, String ipAddress, AllergyCorrectionRequest req) {
        tenantContext.set(tenantId);
        // V1-005: take the patient's register lock, then the row lock, and merge against the locked values. Two
        // concurrent corrections now run one after the other, each seeing the other's result, so a stale
        // read can no longer write an old severity back. Each applied correction has its own history row.
        AllergyRow unlocked = repo.findById(tenantId, id).orElseThrow(ApiException::notFound);
        repo.lockRegister(tenantId, unlocked.patientId());
        AllergyRow current = repo.findByIdForUpdate(tenantId, id).orElseThrow(ApiException::notFound);
        if (!current.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "allergy-removed", "Allergy removed",
                    "This allergy was removed from the register and can no longer be corrected. Record it again if it applies.");
        }
        String reason = req.reason().trim();
        if (reason.isEmpty()) {
            throw reasonRequired();
        }
        String substance = req.substance() == null ? current.substance() : req.substance().trim();
        if (substance.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Substance cannot be blank.");
        }
        String category = req.category() == null ? current.category() : req.category();
        String severity = req.severity() == null ? current.severity() : req.severity();
        String reaction = req.reaction() == null ? current.reaction() : blankToNull(req.reaction());
        String source = req.source() == null ? current.source() : req.source();
        if (Objects.equals(substance, current.substance()) && Objects.equals(category, current.category())
                && Objects.equals(severity, current.severity()) && Objects.equals(reaction, current.reaction())
                && Objects.equals(source, current.source())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to correct", "The correction does not change any field.");
        }
        Map<String, Object> before = snapshot(current);
        repo.applyCorrection(tenantId, id, substance, category, severity, reaction, source, callerStaffId);
        AllergyRow updated = repo.findById(tenantId, id).orElseThrow();
        Map<String, Object> after = snapshot(updated);
        repo.insertEvent(tenantId, current.patientId(), id, "corrected", json(before), json(after), reason, callerStaffId);
        allergyChanged(tenantId, current.patientId(), Map.of("allergyId", id, "change", "corrected", "status", "active"));
        Map<String, Object> auditAfter = new LinkedHashMap<>(after);
        auditAfter.put("reason", reason);
        audit(tenantId, callerStaffId, ipAddress, "allergy.corrected", id, before, auditAfter);
        return toResponse(updated, repo.findStaffNames(tenantId, Set.of(updated.recordedBy())).get(updated.recordedBy()), null);
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID id, UUID callerStaffId, String ipAddress, AllergyRemovalRequest req) {
        tenantContext.set(tenantId);
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw reasonRequired();
        }
        AllergyRow unlocked = repo.findById(tenantId, id).orElseThrow(ApiException::notFound);
        repo.lockRegister(tenantId, unlocked.patientId());
        AllergyRow current = repo.findByIdForUpdate(tenantId, id).orElseThrow(ApiException::notFound);
        if (!current.active()) {
            return; // already removed: idempotent, nothing further to record
        }
        Map<String, Object> before = snapshot(current);
        if (repo.deactivate(tenantId, id, callerStaffId, reason) == 0) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("active", false);
        repo.insertEvent(tenantId, current.patientId(), id, "removed", json(before), json(after), reason, callerStaffId);
        allergyChanged(tenantId, current.patientId(), Map.of("allergyId", id, "change", "removed", "status", "removed"));
        Map<String, Object> auditAfter = new LinkedHashMap<>(after);
        auditAfter.put("reason", reason);
        audit(tenantId, callerStaffId, ipAddress, "allergy.removed", id, before, auditAfter);
    }

    @Transactional
    public List<AllergyEventResponse> history(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        List<AllergyEventRow> rows = repo.findEvents(tenantId, patientId);
        Set<UUID> actors = new HashSet<>();
        rows.forEach(r -> actors.add(r.actorId()));
        Map<UUID, String> names = repo.findStaffNames(tenantId, actors);
        return rows.stream().map(r -> {
            Map<String, Object> before = parse(r.beforeJson());
            Map<String, Object> after = parse(r.afterJson());
            Object substance = after != null ? after.get("substance") : before != null ? before.get("substance") : null;
            return new AllergyEventResponse(r.id(), r.allergyId(), r.event(), substance == null ? null : substance.toString(),
                    before, after, r.reason(), r.actorId(), names.get(r.actorId()), r.occurredAt());
        }).toList();
    }

    @Transactional
    public AllergyStatusResponse status(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        int active = repo.findActiveByPatient(tenantId, patientId).size();
        if (active > 0) {
            return new AllergyStatusResponse("recorded", active, null, null, null);
        }
        AssessmentRow a = repo.findAssessment(tenantId, patientId).orElse(null);
        if (a == null) {
            return new AllergyStatusResponse("not_assessed", 0, null, null, null);
        }
        return new AllergyStatusResponse("none_documented", 0, a.documentedBy(),
                repo.findStaffNames(tenantId, Set.of(a.documentedBy())).get(a.documentedBy()), a.documentedAt());
    }

    /** "Asked, and the patient has no known allergies": a deliberate clinician statement, audited. */
    @Transactional
    public AllergyStatusResponse documentNoKnownAllergies(UUID tenantId, UUID patientId, UUID callerStaffId, String ipAddress) {
        tenantContext.set(tenantId);
        if (!repo.patientExists(tenantId, patientId)) {
            throw ApiException.notFound();
        }
        repo.lockRegister(tenantId, patientId);
        if (!repo.findActiveByPatient(tenantId, patientId).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "allergies-recorded", "Allergies are recorded",
                    "This patient has active allergies on the register, so 'no known allergies' cannot be documented.");
        }
        repo.upsertAssessment(tenantId, patientId, callerStaffId);
        audit(tenantId, callerStaffId, ipAddress, "allergy.no_known_allergies_documented", patientId, null, Map.of("status", "no_known_allergies"));
        allergyChanged(tenantId, patientId, Map.of("change", "no_known_allergies", "status", "none_documented"));
        return status(tenantId, patientId);
    }

    /** OPS-015: ids and codes only; substance, severity and reaction never leave the register in an event. */
    private void allergyChanged(UUID tenantId, UUID patientId, Map<String, Object> data) {
        var body = new LinkedHashMap<String, Object>(data);
        body.put("patientId", patientId);
        events.publish(tenantId, com.nabd.hms.common.DomainEventType.ALLERGY_CHANGED, patientId, body);
    }

    private void audit(UUID tenantId, UUID staffId, String ip, String action, UUID entityId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, action, "patient_allergy", entityId, before, after);
    }

    private Map<String, Object> snapshot(AllergyRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("substance", a.substance());
        m.put("category", a.category());
        m.put("severity", a.severity());
        m.put("reaction", a.reaction());
        m.put("source", a.source());
        m.put("active", a.active());
        m.put("allergenIngredientId", a.allergenIngredientId()); // CLN-021: history rows carry the codes
        m.put("allergenClassId", a.allergenClassId());
        return m;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("allergy snapshot is not serializable", e);
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_MAP);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private AllergyResponse toResponse(AllergyRow a, String recordedByName, String deactivatedByName) {
        return new AllergyResponse(a.id(), a.patientId(), a.substance(), a.severity(), a.reaction(), a.active(),
                a.recordedBy(), a.recordedAt(), a.category(), a.source(), recordedByName, a.updatedAt(),
                a.deactivationReason(), a.deactivatedAt(), deactivatedByName, a.unconfirmed(),
                a.allergenIngredientId(), a.allergenClassId());
    }

    private ApiException reasonRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                "A reason is required to correct or remove an allergy. The original entry is kept in the allergy history.");
    }
}
