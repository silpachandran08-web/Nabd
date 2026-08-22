package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import com.nabd.hms.queue.dto.QueueReorderRequest;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.QueueEntryRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    /**
     * Strict linear order (NB-094); no_show is reachable from anywhere before checkout. v2's
     * wireframe lists "vitals pending/done" as two distinct states, not the single "vitals" step
     * v1 had — split here to match.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "checked_in", Set.of("waiting", "no_show"),
            "waiting", Set.of("vitals_pending", "no_show"),
            "vitals_pending", Set.of("vitals_done", "no_show"),
            "vitals_done", Set.of("in_consult", "no_show"),
            "in_consult", Set.of("checkout_pending", "no_show"),
            "checkout_pending", Set.of("completed"),
            "completed", Set.of(),
            "no_show", Set.of()
    );

    private final QueueRepository repo;
    private final AppointmentRepository appointmentRepo;
    private final AppointmentService appointmentService;
    private final ScheduleRepository scheduleRepo;
    private final TenantContext tenantContext;

    QueueService(QueueRepository repo, AppointmentRepository appointmentRepo, AppointmentService appointmentService,
                 ScheduleRepository scheduleRepo, TenantContext tenantContext) {
        this.repo = repo;
        this.appointmentRepo = appointmentRepo;
        this.appointmentService = appointmentService;
        this.scheduleRepo = scheduleRepo;
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

        int token = repo.nextTokenNumber(req.doctorId(), today);
        String source = req.source() == null ? "walk_in" : req.source();
        UUID id = repo.insert(tenantId, req.appointmentId(), req.patientId(), req.doctorId(), today, token, source);
        log.info("queue check-in by {}: token {} for doctor {} ({}), entry {}",
                callerStaffId, token, req.doctorId(), req.appointmentId() == null ? "walk-in" : "scheduled", id);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public List<QueueEntryResponse> list(UUID tenantId, UUID doctorId, LocalDate date, boolean priorityOnly) {
        tenantContext.set(tenantId);
        LocalDate day = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        return repo.listForDay(tenantId, doctorId, day, priorityOnly).stream().map(this::toResponse).toList();
    }

    @Transactional
    public QueueEntryResponse updateStatus(UUID tenantId, UUID callerStaffId, UUID id, QueueStatusUpdateRequest req) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);

        Set<String> allowed = TRANSITIONS.getOrDefault(current.status(), Set.of());
        if (!allowed.contains(req.status())) {
            log.warn("illegal queue transition blocked by {}: entry {} {} -> {}", callerStaffId, id, current.status(), req.status());
            throw new ApiException(HttpStatus.BAD_REQUEST, "illegal-transition", "Illegal queue transition",
                    "Cannot move from " + current.status() + " to " + req.status() + ".");
        }

        repo.updateStatus(tenantId, id, req.status());
        log.info("queue entry {} moved {} -> {} by {}", id, current.status(), req.status(), callerStaffId);
        if (current.appointmentId() != null && ("completed".equals(req.status()) || "no_show".equals(req.status()))) {
            appointmentService.markTerminal(tenantId, current.appointmentId(), req.status());
        }
        return toResponse(repo.findById(tenantId, id).orElseThrow());
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
                row.queueDate(), row.tokenNumber(), row.status(), row.priority(), row.priorityReason(),
                row.priorityFlaggedBy(), row.priorityFlaggedAt(), row.priorityAcknowledgedBy(),
                row.priorityAcknowledgedAt(), row.source(), row.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException sessionFull() {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                "This session has reached its patient cap (walk-ins and bookings counted together).");
    }
}
