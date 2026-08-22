package com.nabd.hms.nursing;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.nursing.dto.ActivityEntryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** NB-148: the nurse's own read-only record of today's completed work — a read across vitals,
 * administration_records and queue_entries' priority flags (all already written by this epic and
 * NB-106), not a new table to keep in sync. */
@Service
public class ActivityService {

    private final NursingRepository repo;
    private final TenantContext tenantContext;

    ActivityService(NursingRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<ActivityEntryResponse> today(UUID tenantId, UUID staffId) {
        tenantContext.set(tenantId);
        return repo.listActivityForStaffToday(tenantId, staffId, LocalDate.now()).stream()
                .map(r -> new ActivityEntryResponse(r.kind(), r.activity(),
                        repo.findPatientName(tenantId, r.patientId()).orElse("Unknown patient"), r.occurredAt()))
                .toList();
    }
}
