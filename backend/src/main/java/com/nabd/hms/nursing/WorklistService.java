package com.nabd.hms.nursing;

import com.nabd.hms.clinical.VitalsService;
import com.nabd.hms.clinical.dto.VitalsResponse;
import com.nabd.hms.nursing.dto.WorklistResponse;
import com.nabd.hms.patient.PatientService;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import com.nabd.hms.queue.QueueService;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NUR-019: the nurse page's GET /queue (today, no filters) + per-row GET /patients/{id} + GET /clinical/vitals/{id}
 * in one call. Entries come from QueueService.list exactly as GET /queue returns them; patients and vitals are
 * batched. Patient is null without patients:view (or when GET /patients/{id} would refuse/404 it); vitals are null
 * without clinical:view or when none are recorded. Rows are never dropped.
 */
@Service
public class WorklistService {

    private final QueueService queueService;
    private final PatientService patientService;
    private final VitalsService vitalsService;

    WorklistService(QueueService queueService, PatientService patientService, VitalsService vitalsService) {
        this.queueService = queueService;
        this.patientService = patientService;
        this.vitalsService = vitalsService;
    }

    @Transactional
    public WorklistResponse worklist(UUID tenantId, UUID callerStaffId, boolean canViewPatients, boolean canViewClinical) {
        List<QueueEntryResponse> entries = queueService.list(tenantId, null, null, null, false);
        Map<UUID, PatientDetailResponse> patients = canViewPatients
                ? patientService.getVisibleMany(tenantId, callerStaffId, entries.stream().map(QueueEntryResponse::patientId).toList())
                : Map.of();
        Map<UUID, VitalsResponse> vitals = canViewClinical
                ? vitalsService.getMany(tenantId, entries.stream().map(QueueEntryResponse::id).toList())
                : Map.of();
        return new WorklistResponse(entries.stream()
                .map(e -> new WorklistResponse.Item(e, patients.get(e.patientId()), vitals.get(e.id())))
                .toList());
    }
}
