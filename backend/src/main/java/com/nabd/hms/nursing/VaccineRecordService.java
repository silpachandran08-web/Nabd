package com.nabd.hms.nursing;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.clinical.ReadScope;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.nursing.dto.VaccineRecordRequest;
import com.nabd.hms.nursing.dto.VaccineRecordResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.nursing.NursingModels.VaccineRecordRow;

/**
 * NUR-B2: record-only vaccine log. It stores exactly what the nurse entered. There is no schedule,
 * no due-date and no series logic anywhere in this class on purpose: no immunisation schedule
 * reference data exists, so a "due list" is not offered.
 */
@Service
public class VaccineRecordService {

    private final NursingRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService auditService;

    private final ActorDirectory actors;

    VaccineRecordService(NursingRepository repo, TenantContext tenantContext, ClinicClock clock, AuditService auditService, ActorDirectory actors) {
        this.actors = actors;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional
    public VaccineRecordResponse record(UUID tenantId, UUID staffId, VaccineRecordRequest req) {
        tenantContext.set(tenantId);
        repo.findPatientIdIfExists(tenantId, req.patientId()).orElseThrow(this::notFound);
        if (req.queueEntryId() != null && !repo.queueEntryBelongsToPatient(tenantId, req.queueEntryId(), req.patientId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "queue-entry-mismatch", "Visit does not match patient",
                    "The queue entry does not belong to this patient.");
        }
        UUID id = repo.insertVaccineRecord(tenantId, req.patientId(), req.queueEntryId(), staffId, req.vaccineName().trim(),
                req.dose().trim(), req.route(), blankToNull(req.site()), blankToNull(req.lotNumber()), req.expiryDate(),
                blankToNull(req.notes()));
        VaccineRecordResponse out = toResponse(tenantId, repo.findVaccineRecord(tenantId, id).orElseThrow());
        var actor = actors.findActor(tenantId, staffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), null,
                "nursing.vaccine_recorded", "vaccine_record", id, null, auditPayload(out));
        return out;
    }

    @Transactional
    public List<VaccineRecordResponse> listToday(ReadScope scope) {
        UUID tenantId = scope.tenantId();
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId);
        return repo.listVaccineRecords(scope, clock.dayStart(tenantId, today), clock.dayStart(tenantId, today.plusDays(1)))
                .stream().map(r -> toResponse(tenantId, r)).toList();
    }

    /** PHI rule (AuditService): ids and structured values only. No patient or staff name, no free text (vaccine name, dose,
     * site, lot, notes); the full row is recoverable from vaccine_records by entityId. */
    private static Map<String, Object> auditPayload(VaccineRecordResponse out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patientId", out.patientId());
        m.put("queueEntryId", out.queueEntryId());
        m.put("route", out.route());
        m.put("expiryDate", out.expiryDate() == null ? null : out.expiryDate().toString());
        return m;
    }

    private VaccineRecordResponse toResponse(UUID tenantId, VaccineRecordRow r) {
        String patientName = repo.findPatientName(tenantId, r.patientId()).orElse("Unknown patient");
        String recordedBy = repo.findStaffName(tenantId, r.recordedBy()).orElse("Unknown staff");
        return new VaccineRecordResponse(r.id(), r.patientId(), patientName, r.queueEntryId(), r.vaccineName(), r.dose(),
                r.route(), r.site(), r.lotNumber(), r.expiryDate(), r.notes(), recordedBy, r.administeredAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
