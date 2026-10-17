package com.nabd.hms.clinical;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.clinical.dto.VitalsHistoryResponse;
import com.nabd.hms.clinical.dto.VitalsResponse;
import com.nabd.hms.clinical.dto.VitalsWriteRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.QueueService;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.nabd.hms.clinical.VitalsModels.QueueEntrySnapshot;
import static com.nabd.hms.clinical.VitalsModels.VitalsRow;

/**
 * NB-106: closes a real gap, not a speculative one — QueueService's own state machine requires
 * vitals_pending -> vitals_done before a doctor can start a consult, and until this existed nothing
 * in the app could make that transition short of a raw status PATCH. Reuses QueueService directly
 * (same pattern as CheckoutService/NoteService reusing it) rather than duplicating the transition.
 */
@Service
public class VitalsService {

    private final VitalsRepository repo;
    private final QueueService queueService;
    private final TenantContext tenantContext;

    private final ClinicClock clock;
    private final AuditService auditService;

    VitalsService(VitalsRepository repo, QueueService queueService, TenantContext tenantContext, ClinicClock clock,
                  AuditService auditService) {
        this.clock = clock;
        this.auditService = auditService;
        this.repo = repo;
        this.queueService = queueService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public VitalsResponse get(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        VitalsRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return toResponse(tenantId, row);
    }

    /** NUR-019: get() for many visits in two queries, keyed by queue entry id; visits with no vitals are absent. */
    @Transactional
    public java.util.Map<UUID, VitalsResponse> getMany(UUID tenantId, java.util.Collection<UUID> queueEntryIds) {
        if (queueEntryIds.isEmpty()) {
            return java.util.Map.of();
        }
        tenantContext.set(tenantId);
        List<VitalsRow> rows = repo.findByQueueEntries(tenantId, List.copyOf(new java.util.LinkedHashSet<>(queueEntryIds)));
        if (rows.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, LocalDate> dobs = repo.findPatientDobs(tenantId,
                rows.stream().map(VitalsRow::patientId).distinct().toList());
        LocalDate today = clock.today(tenantId);
        java.util.Map<UUID, VitalsResponse> out = new java.util.HashMap<>();
        for (VitalsRow v : rows) {
            LocalDate dob = dobs.get(v.patientId());
            out.put(v.queueEntryId(), toResponse(v, dob == null ? 30 : ageInYears(dob, today))); // 30 = get()'s fallback
        }
        return out;
    }

    /** NUR-022: previous vitals of a patient across visits, newest first (the caller drops the current visit). */
    @Transactional
    public VitalsHistoryResponse previous(UUID tenantId, UUID patientId, int limit) {
        tenantContext.set(tenantId);
        if (!repo.patientExists(tenantId, patientId)) {
            throw notFound();
        }
        int ageYears = repo.findPatientDob(tenantId, patientId).map(dob -> ageInYears(dob, clock.today(tenantId))).orElse(30);
        return new VitalsHistoryResponse(repo.findByPatient(tenantId, patientId, limit).stream()
                .map(v -> toResponse(v, ageYears)).toList());
    }

    @Transactional
    public VitalsResponse record(UUID tenantId, UUID callerStaffId, UUID queueEntryId, VitalsWriteRequest req,
                                 boolean canCorrect) {
        tenantContext.set(tenantId);
        QueueEntrySnapshot snapshot = repo.findQueueEntrySnapshot(tenantId, queueEntryId).orElseThrow(this::notFound);

        // An empty save must never move the visit on to the doctor with no vitals, and BP is a pair.
        if (req.heightCm() == null && req.weightKg() == null && req.bpSystolic() == null && req.bpDiastolic() == null
                && req.pulseBpm() == null && req.tempCelsius() == null && req.spo2Percent() == null
                && req.bloodSugar() == null && req.painScore() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-measurement", "No measurement",
                    "Enter at least one measurement before saving vitals.");
        }
        if ((req.bpSystolic() == null) != (req.bpDiastolic() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "incomplete-blood-pressure", "Incomplete blood pressure",
                    "Enter both systolic and diastolic blood pressure, or neither.");
        }
        if (req.bpSystolic() != null && req.bpSystolic() <= req.bpDiastolic()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-blood-pressure", "Invalid blood pressure",
                    "Systolic pressure must be higher than diastolic pressure.");
        }

        VitalsRow previous = repo.findByQueueEntry(tenantId, queueEntryId).orElse(null);
        if (!canCorrect && previous != null && changesRecordedValue(previous, req)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "vitals-correction-forbidden", "Vitals correction not permitted",
                    "Correcting an already-recorded vital sign needs the clinical edit permission.");
        }
        repo.upsert(tenantId, queueEntryId, snapshot.patientId(), req.heightCm(), req.weightKg(),
                req.bpSystolic(), req.bpDiastolic(), req.pulseBpm(), req.tempCelsius(), req.spo2Percent(),
                req.bloodSugar(), req.painScore(), callerStaffId);

        if ("vitals_pending".equals(snapshot.status())) {
            queueService.updateStatus(tenantId, callerStaffId, queueEntryId, new QueueStatusUpdateRequest("vitals_done"));
        }
        VitalsRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        auditCorrection(tenantId, callerStaffId, queueEntryId, previous, row);
        return toResponse(tenantId, row);
    }

    /** A later save that changes an already-recorded value is a correction of a clinical measurement: the old and new
     * values of the fields that changed go to the audit trail (nothing is overwritten without a trace). Measured values are
     * kept here on purpose: vitals has no history table and the upsert overwrites the row, so audit_log is the only record
     * of the old reading (see the PHI policy on AuditService). */
    private void auditCorrection(UUID tenantId, UUID staffId, UUID queueEntryId, VitalsRow before, VitalsRow after) {
        if (before == null) {
            return;
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff("heightCm", before.heightCm(), after.heightCm(), was, now);
        diff("weightKg", before.weightKg(), after.weightKg(), was, now);
        diff("bpSystolic", before.bpSystolic(), after.bpSystolic(), was, now);
        diff("bpDiastolic", before.bpDiastolic(), after.bpDiastolic(), was, now);
        diff("pulseBpm", before.pulseBpm(), after.pulseBpm(), was, now);
        diff("tempCelsius", before.tempCelsius(), after.tempCelsius(), was, now);
        diff("spo2Percent", before.spo2Percent(), after.spo2Percent(), was, now);
        diff("bloodSugar", before.bloodSugar(), after.bloodSugar(), was, now);
        diff("painScore", before.painScore(), after.painScore(), was, now);
        if (now.isEmpty()) {
            return;
        }
        VitalsModels.ActorInfo actor = repo.findActorInfo(tenantId, staffId).orElseThrow(this::notFound);
        now.put("queueEntryId", queueEntryId.toString());
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), null, "vitals.corrected", "vitals",
                after.id(), was, now);
    }

    /** REC-060: true when the request changes a value that is already recorded (null in the request = unchanged;
     * filling a still-empty field or re-sending the same value is not a correction). */
    private static boolean changesRecordedValue(VitalsRow v, VitalsWriteRequest r) {
        return changes(v.heightCm(), r.heightCm()) || changes(v.weightKg(), r.weightKg())
                || changes(v.bpSystolic(), r.bpSystolic()) || changes(v.bpDiastolic(), r.bpDiastolic())
                || changes(v.pulseBpm(), r.pulseBpm()) || changes(v.tempCelsius(), r.tempCelsius())
                || changes(v.spo2Percent(), r.spo2Percent()) || changes(v.bloodSugar(), r.bloodSugar())
                || changes(v.painScore(), r.painScore());
    }

    private static boolean changes(Object recorded, Object requested) {
        if (recorded == null || requested == null) {
            return false;
        }
        return recorded instanceof java.math.BigDecimal b && requested instanceof java.math.BigDecimal a
                ? b.compareTo(a) != 0 : !recorded.equals(requested);
    }

    private static void diff(String field, Object before, Object after, Map<String, Object> was, Map<String, Object> now) {
        boolean same = before instanceof java.math.BigDecimal b && after instanceof java.math.BigDecimal a
                ? b.compareTo(a) == 0 : Objects.equals(before, after);
        if (!same) {
            was.put(field, before);
            now.put(field, after);
        }
    }

    private VitalsResponse toResponse(UUID tenantId, VitalsRow v) {
        int ageYears = repo.findPatientDob(tenantId, v.patientId()).map(dob -> ageInYears(dob, clock.today(tenantId))).orElse(30);
        return toResponse(v, ageYears);
    }

    private VitalsResponse toResponse(VitalsRow v, int ageYears) {
        List<String> flags = VitalsRanges.flags(v, ageYears);
        return new VitalsResponse(v.id(), v.queueEntryId(), v.patientId(), v.heightCm(), v.weightKg(),
                v.bpSystolic(), v.bpDiastolic(), v.pulseBpm(), v.tempCelsius(), v.spo2Percent(),
                v.bloodSugar(), v.painScore(), v.recordedBy(), v.recordedAt(), flags);
    }

    private static int ageInYears(LocalDate dob, LocalDate today) {
        return Period.between(dob, today).getYears();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
