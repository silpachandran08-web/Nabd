package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.EncounterResponse;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TimelineService {

    private final TimelineRepository repo;
    private final TenantContext tenantContext;

    TimelineService(TimelineRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<EncounterResponse> get(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findForPatient(tenantId, patientId).stream()
                .map(e -> new EncounterResponse(e.queueEntryId(), e.occurredAt(), e.doctorId(), e.diagnosis(),
                        e.assessment(), e.medications()))
                .toList();
    }
}
