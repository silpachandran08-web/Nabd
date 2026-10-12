package com.nabd.hms.clinical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.clinical.PregnancyStatusRepository.StatusRow;
import com.nabd.hms.clinical.dto.PregnancyEventResponse;
import com.nabd.hms.clinical.dto.PregnancyStatusResponse;
import com.nabd.hms.clinical.dto.PregnancyStatusWriteRequest;
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
import java.util.Set;
import java.util.UUID;

/**
 * DOC-021: pregnancy / lactation status, modelled on the allergy register. No row means "not documented" (nobody
 * has asked), which is reported as such and never as "not pregnant". The status is only ever set by an explicit
 * clinician call; nothing derives or clears it. Every change is appended to patient_pregnancy_events and audited
 * (action patient.pregnancy_status.recorded; the audit payload holds the status and dates only, no free text).
 */
@Service
public class PregnancyStatusService {

    public static final String NOT_DOCUMENTED = "not_documented";
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final PregnancyStatusRepository repo;
    private final AllergyRepository allergyRepo;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    PregnancyStatusService(PregnancyStatusRepository repo, AllergyRepository allergyRepo, TenantContext tenantContext,
                           AuditService auditService, ObjectMapper objectMapper) {
        this.repo = repo;
        this.allergyRepo = allergyRepo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PregnancyStatusResponse get(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        if (!allergyRepo.patientExists(tenantId, patientId)) {
            throw notFound();
        }
        return current(tenantId, patientId);
    }

    @Transactional
    public PregnancyStatusResponse record(UUID tenantId, UUID patientId, UUID callerStaffId, String ipAddress, PregnancyStatusWriteRequest req) {
        tenantContext.set(tenantId);
        if (!allergyRepo.patientExists(tenantId, patientId)) {
            throw notFound();
        }
        if (req.expectedDeliveryDate() != null && !"pregnant".equals(req.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "An expected delivery date can only be recorded with the status 'pregnant'.");
        }
        // Same advisory lock as prescription save/sign: a status change cannot slip between a sign-time warning check and the sign.
        allergyRepo.lockRegister(tenantId, patientId);
        StatusRow before = repo.find(tenantId, patientId).orElse(null);
        if (before != null && before.status().equals(req.status())
                && java.util.Objects.equals(before.expectedDeliveryDate(), req.expectedDeliveryDate())) {
            // Nothing changed: no history event, no audit row, and recorded_by / recorded_at keep the original attestation.
            return current(tenantId, patientId);
        }
        repo.upsert(tenantId, patientId, req.status(), req.expectedDeliveryDate(), callerStaffId);
        Map<String, Object> after = snapshot(req.status(), req.expectedDeliveryDate() == null ? null : req.expectedDeliveryDate().toString());
        Map<String, Object> beforeSnap = before == null ? null
                : snapshot(before.status(), before.expectedDeliveryDate() == null ? null : before.expectedDeliveryDate().toString());
        repo.insertEvent(tenantId, patientId, beforeSnap == null ? null : json(beforeSnap), json(after), callerStaffId);
        AllergyModels.ActorInfo actor = allergyRepo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                "patient.pregnancy_status.recorded", "patient_pregnancy_status", patientId, beforeSnap, after);
        return current(tenantId, patientId);
    }

    @Transactional
    public List<PregnancyEventResponse> history(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        List<PregnancyStatusRepository.EventRow> rows = repo.findEvents(tenantId, patientId);
        Set<UUID> actors = new HashSet<>();
        rows.forEach(r -> actors.add(r.actorId()));
        Map<UUID, String> names = allergyRepo.findStaffNames(tenantId, actors);
        return rows.stream().map(r -> new PregnancyEventResponse(r.id(), parse(r.beforeJson()), parse(r.afterJson()),
                r.actorId(), names.get(r.actorId()), r.occurredAt())).toList();
    }

    private PregnancyStatusResponse current(UUID tenantId, UUID patientId) {
        StatusRow row = repo.find(tenantId, patientId).orElse(null);
        if (row == null) {
            return new PregnancyStatusResponse(NOT_DOCUMENTED, null, null, null, null);
        }
        return new PregnancyStatusResponse(row.status(), row.expectedDeliveryDate(), row.recordedBy(),
                allergyRepo.findStaffNames(tenantId, Set.of(row.recordedBy())).get(row.recordedBy()), row.recordedAt());
    }

    private Map<String, Object> snapshot(String status, String edd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("expectedDeliveryDate", edd);
        return m;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("pregnancy snapshot is not serializable", e);
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_MAP);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
