package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.ToothResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.dental.DentalModels.ToothRow;

/** NB-121/122: the specialty-workspace framework's one proof-of-concept — gated entirely by the
 * "specialty_dental" RBAC module grant (see GrantsFlattener), not a new entitlement system. */
@Service
public class DentalService {

    private static final int MIN_FDI_TOOTH = 11;
    private static final int MAX_FDI_TOOTH = 48;

    private final DentalRepository repo;
    private final TenantContext tenantContext;

    DentalService(DentalRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<ToothResponse> chart(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findByPatient(tenantId, patientId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ToothResponse upsertTooth(UUID tenantId, UUID patientId, int toothNumber, UUID callerStaffId,
                                      String status, String note) {
        if (toothNumber < MIN_FDI_TOOTH || toothNumber > MAX_FDI_TOOTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-tooth", "Invalid tooth number",
                    "toothNumber must be a valid FDI two-digit code (11-48).");
        }
        tenantContext.set(tenantId);
        repo.upsert(tenantId, patientId, toothNumber, status == null ? "healthy" : status, note, callerStaffId);
        return repo.findByPatient(tenantId, patientId).stream()
                .filter(t -> t.toothNumber() == toothNumber).findFirst().map(this::toResponse).orElseThrow();
    }

    private ToothResponse toResponse(ToothRow t) {
        return new ToothResponse(t.id(), t.patientId(), t.toothNumber(), t.status(), t.note(),
                t.updatedBy(), t.updatedAt());
    }
}
