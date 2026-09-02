package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.department.DepartmentService;
import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import com.nabd.hms.queue.dto.QueueReorderRequest;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import com.nabd.hms.queue.dto.TransferRequest;
import com.nabd.hms.queue.dto.TransferResponse;
import com.nabd.hms.queue.dto.WaitEstimateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.QueueEntryRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    /**
     * NB-355: the linear order is no longer fixed in code — DepartmentService.resolveStatusSequence()
     * is the single source of truth for a department's configured (or default) pipeline shape;
     * this is a pure lookup over whatever sequence it returns. no_show is reachable from anywhere
     * before the terminal checkout_pending step, matching the original NB-094 rule. transferred_out
     * is a second terminal alongside checkout_pending, reachable only from in_consult — a doctor's
     * end-of-consult choice between billing this leg or moving the patient into another
     * department's queue.
     */
    private static Set<String> allowedNextStatuses(String current, List<String> sequence) {
        if (Set.of("completed", "no_show", "transferred_out").contains(current)) {
            return Set.of();
        }
        int i = sequence.indexOf(current);
        Set<String> next = i >= 0 && i + 1 < sequence.size() ? Set.of(sequence.get(i + 1)) : Set.of();
        if ("in_consult".equals(current)) {
            next = union(next, "transferred_out");
        }
        if (!"checkout_pending".equals(current)) {
            next = union(next, "no_show");
        }
        return next;
    }

    private static Set<String> union(Set<String> set, String extra) {
        Set<String> combined = new java.util.HashSet<>(set);
        combined.add(extra);
        return combined;
    }

    private final QueueRepository repo;
    private final AppointmentRepository appointmentRepo;
    private final AppointmentService appointmentService;
    private final ScheduleRepository scheduleRepo;
    private final DepartmentService departmentService;
    private final TenantContext tenantContext;

    QueueService(QueueRepository repo, AppointmentRepository appointmentRepo, AppointmentService appointmentService,
                 ScheduleRepository scheduleRepo, DepartmentService departmentService, TenantContext tenantContext) {
        this.repo = repo;
        this.appointmentRepo = appointmentRepo;
        this.appointmentService = appointmentService;
        this.scheduleRepo = scheduleRepo;
        this.departmentService = departmentService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public QueueEntryResponse checkIn(UUID tenantId, UUID callerStaffId, CheckInRequest req) {
        tenantContext.set(tenantId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        if (req.appointmentId() != null) {
            appointmentRepo.findById(tenantId, req.appointmentId())
                    .filter(a -> "scheduled".equals(a.status()))
                    .filter(a -> a.patientId().equals(req.patientId()) && a.doctorId().equals(req.doctorId()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                            "Appointment not found, already resolved, or doesn't match patient/doctor."));
        }

        // serializes both "what's the next token number" and, for walk-ins, the session-capacity
        // check below against AppointmentService.book() — same pool, same lock (QueueRepository.lockDoctorDay)
        repo.lockDoctorDay(req.doctorId(), today);

        if (req.appointmentId() == null) {
            enforceSessionCapacity(req.doctorId(), today); // scheduled check-ins were already capacity-checked at booking
        }

        UUID departmentId = repo.findCheckInDepartment(tenantId, req.doctorId());
        int token = repo.nextTokenNumber(req.doctorId(), today);
        String source = req.source() == null ? "walk_in" : req.source();
        UUID id = repo.insert(tenantId, req.appointmentId(), req.patientId(), req.doctorId(), departmentId,
                null, today, token, source, "checked_in");
        log.info("queue check-in by {}: token {} for doctor {} ({}), entry {}",
                callerStaffId, token, req.doctorId(), req.appointmentId() == null ? "walk-in" : "scheduled", id);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public List<QueueEntryResponse> list(UUID tenantId, UUID doctorId, UUID departmentId, LocalDate date, boolean priorityOnly) {
        tenantContext.set(tenantId);
        LocalDate day = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        return repo.listForDay(tenantId, doctorId, departmentId, day, priorityOnly).stream().map(this::toResponse).toList();
    }

    @Transactional
    public QueueEntryResponse updateStatus(UUID tenantId, UUID callerStaffId, UUID id, QueueStatusUpdateRequest req) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        requireLegalTransition(tenantId, current, req.status(), callerStaffId, id);

        repo.updateStatus(tenantId, id, req.status());
        log.info("queue entry {} moved {} -> {} by {}", id, current.status(), req.status(), callerStaffId);
        if (current.appointmentId() != null && ("completed".equals(req.status()) || "no_show".equals(req.status()))) {
            appointmentService.markTerminal(tenantId, current.appointmentId(), req.status());
        }
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    private void requireLegalTransition(UUID tenantId, QueueEntryRow current, String nextStatus, UUID callerStaffId, UUID id) {
        List<String> sequence = departmentService.resolveStatusSequence(tenantId, current.departmentId());
        Set<String> allowed = allowedNextStatuses(current.status(), sequence);
        if (!allowed.contains(nextStatus)) {
            log.warn("illegal queue transition blocked by {}: entry {} {} -> {}", callerStaffId, id, current.status(), nextStatus);
            throw new ApiException(HttpStatus.BAD_REQUEST, "illegal-transition", "Illegal queue transition",
                    "Cannot move from " + current.status() + " to " + nextStatus + ".");
        }
    }

    /** NB-3xx: a doctor's end-of-consult decision to move the patient into another department's
     * queue instead of (or, via a later visit, in addition to) billing this leg. Closes the current
     * leg (transferred_out, subject to the same legality check as any other transition) and opens a
     * fresh one in the target department, starting at "waiting" rather than "checked_in" — the
     * patient is already on-site, so the arrival-acknowledgment step is redundant for a same-visit
     * transfer. Reuses the entire existing pipeline for the new leg; it just enters partway through. */
    @Transactional
    public TransferResponse transfer(UUID tenantId, UUID callerStaffId, UUID id, TransferRequest req) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        requireLegalTransition(tenantId, current, "transferred_out", callerStaffId, id);

        if (!repo.transferAllowed(tenantId, current.departmentId(), req.toDepartmentId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transfer-not-allowed", "Transfer not allowed",
                    "This department isn't configured to transfer patients to the requested department.");
        }

        repo.updateStatus(tenantId, id, "transferred_out");
        log.info("queue entry {} transferred_out by {} (patient {} -> department {})",
                id, callerStaffId, current.patientId(), req.toDepartmentId());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        repo.lockDoctorDay(req.doctorId(), today);
        int token = repo.nextTokenNumber(req.doctorId(), today);
        UUID newId = repo.insert(tenantId, null, current.patientId(), req.doctorId(), req.toDepartmentId(),
                current.id(), today, token, "internal_transfer", "waiting");
        log.info("queue entry {} opened by transfer from {} (doctor {}, department {})",
                newId, id, req.doctorId(), req.toDepartmentId());

        return new TransferResponse(
                toResponse(repo.findById(tenantId, id).orElseThrow()),
                toResponse(repo.findById(tenantId, newId).orElseThrow()));
    }

    @Transactional
    public QueueEntryResponse reorder(UUID tenantId, UUID callerStaffId, UUID id, QueueReorderRequest req) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, id).orElseThrow(this::notFound);
        repo.updatePriority(tenantId, id, req.priority(), req.priority() ? req.reason() : null, callerStaffId);
        log.info("queue entry {} priority set to {} by {} ({})", id, req.priority(), callerStaffId, req.reason());
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** NB-143: the "alerts the doctor" half of the AC — a doctor (or anyone with queue:edit)
     * acknowledging a flag they've seen, tracked and displayed, not just a passive dot. */
    @Transactional
    public QueueEntryResponse acknowledgePriority(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (!current.priority()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-flagged", "Not flagged",
                    "This visit isn't flagged as priority.");
        }
        repo.acknowledgePriority(tenantId, id, callerStaffId);
        log.info("queue entry {} priority acknowledged by {}", id, callerStaffId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    // ponytail: 30-visit sample, most-recent-first — enough to smooth out one unusually long/short
    // visit without dragging in a doctor's schedule from months ago. Revisit if a doctor's typical
    // visit length genuinely drifts week to week and this window turns out too short to track it.
    private static final int WAIT_ESTIMATE_SAMPLE_SIZE = 30;
    private static final double DEFAULT_VISIT_MINUTES = 15.0; // no billed history yet — same default as slot length

    /** NB-101: estimated minutes until a patient checking in right now sees this doctor. */
    @Transactional
    public WaitEstimateResponse waitEstimate(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        Optional<Double> avg = repo.averageRecentVisitMinutes(tenantId, doctorId, WAIT_ESTIMATE_SAMPLE_SIZE);
        double avgVisitMinutes = avg.orElse(DEFAULT_VISIT_MINUTES);
        int patientsAhead = repo.countActiveAhead(tenantId, doctorId, LocalDate.now(ZoneOffset.UTC));
        int estimatedMinutes = (int) Math.round(avgVisitMinutes * patientsAhead);
        return new WaitEstimateResponse(estimatedMinutes, patientsAhead, avgVisitMinutes, avg.isPresent());
    }

    /** NB-098 — same session cap AppointmentService.book() enforces, applied to the walk-in path. */
    private void enforceSessionCapacity(UUID doctorId, LocalDate today) {
        LocalTime now = Instant.now().atZone(ZoneOffset.UTC).toLocalTime();
        int dayOfWeek = today.getDayOfWeek().getValue() % 7;

        Optional<WorkingHoursRow> block = scheduleRepo.findBlockCovering(doctorId, dayOfWeek, now);
        if (block.isEmpty() || block.get().maxPatients() == null) {
            return;
        }

        int occupancy = scheduleRepo.countSessionOccupancy(doctorId, today, block.get().startTime(), block.get().endTime());
        if (occupancy >= block.get().maxPatients()) {
            log.warn("session capacity reached (walk-in check-in): doctor {} on {} ({}/{})",
                    doctorId, today, occupancy, block.get().maxPatients());
            throw sessionFull();
        }
    }

    private QueueEntryResponse toResponse(QueueEntryRow row) {
        return new QueueEntryResponse(row.id(), row.appointmentId(), row.patientId(), row.doctorId(),
                row.departmentId(), row.parentQueueEntryId(), row.queueDate(), row.tokenNumber(), row.status(),
                row.priority(), row.priorityReason(), row.priorityFlaggedBy(), row.priorityFlaggedAt(),
                row.priorityAcknowledgedBy(), row.priorityAcknowledgedAt(), row.source(), row.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException sessionFull() {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                "This session has reached its patient cap (walk-ins and bookings counted together).");
    }
}
