package com.nabd.hms.clinical.dental;

import com.nabd.hms.clinical.dental.dto.ToothHistoryEntryResponse;
import com.nabd.hms.clinical.dental.dto.ToothResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.clinical.dental.DentalModels.ActorInfo;
import static com.nabd.hms.clinical.dental.DentalModels.ToothRow;

/** NB-121/122: the specialty-workspace framework's one proof-of-concept — gated entirely by the
 * "specialty_dental" RBAC module grant (see GrantsFlattener), not a new entitlement system.
 *
 * NB-127: every write is audited via the shared AuditService, scoped to this one
 * dental_chart_entries row (entity_id = the row's own id, stable across updates) — that's the
 * tooth's full timeline, no second history table needed. */
@Service
public class DentalService {

    private static final int MIN_FDI_TOOTH = 11;
    private static final int MAX_FDI_TOOTH = 48;

    private final DentalRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    DentalService(DentalRepository repo, AuditService auditService, TenantContext tenantContext) {
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<ToothResponse> chart(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findByPatient(tenantId, patientId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ToothResponse upsertTooth(UUID tenantId, UUID patientId, int toothNumber, UUID callerStaffId,
                                      String ipAddress, String status, String note) {
        requireValidTooth(toothNumber);
        tenantContext.set(tenantId);
        String resolvedStatus = status == null ? "healthy" : status;

        ToothRow before = repo.findByToothNumber(tenantId, patientId, toothNumber).orElse(null);
        // A status-only correction (the arrivals/patients UI never sends note) must not wipe a
        // previously recorded note — found live while verifying NB-127's history, same class of bug
        // fixed below for updateSupernumerary().
        String resolvedNote = note == null && before != null ? before.note() : note;
        repo.upsert(tenantId, patientId, toothNumber, resolvedStatus, resolvedNote, callerStaffId);
        ToothRow after = repo.findByToothNumber(tenantId, patientId, toothNumber).orElseThrow();

        audit(tenantId, callerStaffId, ipAddress, before == null ? "dental_chart.create" : "dental_chart.update",
                after.id(), before, after);
        return toResponse(after);
    }

    @Transactional
    public ToothResponse addSupernumerary(UUID tenantId, UUID patientId, UUID callerStaffId, String ipAddress,
                                           int nearToothNumber, String status, String note) {
        requireValidTooth(nearToothNumber);
        tenantContext.set(tenantId);
        String resolvedStatus = status == null ? "healthy" : status;

        UUID id = repo.insertSupernumerary(tenantId, patientId, nearToothNumber, resolvedStatus, note, callerStaffId);
        ToothRow after = repo.findById(tenantId, patientId, id).orElseThrow();
        audit(tenantId, callerStaffId, ipAddress, "dental_chart.create", id, null, after);
        return toResponse(after);
    }

    @Transactional
    public ToothResponse updateSupernumerary(UUID tenantId, UUID patientId, UUID id, UUID callerStaffId,
                                              String ipAddress, String status, String note) {
        tenantContext.set(tenantId);
        ToothRow before = repo.findById(tenantId, patientId, id).filter(ToothRow::isSupernumerary).orElseThrow(this::notFound);
        if (repo.updateSupernumerary(tenantId, patientId, id, status == null ? before.status() : status,
                note == null ? before.note() : note, callerStaffId) == 0) {
            throw notFound();
        }
        ToothRow after = repo.findById(tenantId, patientId, id).orElseThrow();
        audit(tenantId, callerStaffId, ipAddress, "dental_chart.update", id, before, after);
        return toResponse(after);
    }

    @Transactional
    public void removeSupernumerary(UUID tenantId, UUID patientId, UUID id, UUID callerStaffId, String ipAddress) {
        tenantContext.set(tenantId);
        ToothRow before = repo.findById(tenantId, patientId, id).filter(ToothRow::isSupernumerary).orElseThrow(this::notFound);
        if (repo.deleteSupernumerary(tenantId, patientId, id) == 0) {
            throw notFound();
        }
        audit(tenantId, callerStaffId, ipAddress, "dental_chart.delete", id, before, null);
    }

    @Transactional
    public List<ToothHistoryEntryResponse> history(UUID tenantId, UUID patientId, UUID toothId) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, patientId, toothId).orElseThrow(this::notFound);
        return repo.findHistory(tenantId, toothId).stream()
                .map(h -> new ToothHistoryEntryResponse(h.actorName(), h.actorRole(), h.action(), h.before(),
                        h.after(), h.occurredAt()))
                .toList();
    }

    private void audit(UUID tenantId, UUID callerStaffId, String ipAddress, String action, UUID entityId,
                        ToothRow before, ToothRow after) {
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                action, "dental_chart_entries", entityId, snapshot(before), snapshot(after));
    }

    private Map<String, Object> snapshot(ToothRow t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("toothNumber", t.toothNumber());
        m.put("status", t.status());
        m.put("note", t.note());
        m.put("isSupernumerary", t.isSupernumerary());
        return m;
    }

    private void requireValidTooth(int toothNumber) {
        if (toothNumber < MIN_FDI_TOOTH || toothNumber > MAX_FDI_TOOTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-tooth", "Invalid tooth number",
                    "toothNumber must be a valid FDI two-digit code (11-48).");
        }
    }

    private ToothResponse toResponse(ToothRow t) {
        return new ToothResponse(t.id(), t.patientId(), t.toothNumber(), t.status(), t.note(),
                t.isSupernumerary(), t.updatedBy(), t.updatedAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
