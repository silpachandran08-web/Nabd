package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.AllergyResponse;
import com.nabd.hms.clinical.dto.AllergyWriteRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;

/** NB-107/108: register + hard-warning. Severity/reaction are free-text against a fixed vocabulary
 * (mild/moderate/severe) — not a coded allergen list, since this app has no such reference data. */
@Service
public class AllergyService {

    private final AllergyRepository repo;
    private final TenantContext tenantContext;

    AllergyService(AllergyRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<AllergyResponse> list(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findActiveByPatient(tenantId, patientId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public AllergyResponse add(UUID tenantId, UUID patientId, UUID callerStaffId, AllergyWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insert(tenantId, patientId, req.substance(), req.severity(), req.reaction(), callerStaffId);
        return repo.findActiveByPatient(tenantId, patientId).stream()
                .filter(a -> a.id().equals(id)).findFirst().map(this::toResponse).orElseThrow();
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        if (repo.deactivate(tenantId, id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
        }
    }

    private AllergyResponse toResponse(AllergyRow a) {
        return new AllergyResponse(a.id(), a.patientId(), a.substance(), a.severity(), a.reaction(),
                a.active(), a.recordedBy(), a.recordedAt());
    }
}
