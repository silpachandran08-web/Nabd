package com.nabd.hms.queue;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.OrganizationConfig;
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
import java.time.ZoneId;
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
     * this is a pure lookup over whatever sequence it returns. no_show is reachable only before
     * in_consult (ACC-002). "completed" is never a PATCH target: billing's VisitCommands owns it. transferred_out
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
        int consult = sequence.indexOf("in_consult");
        if (i >= 0 && (consult < 0 ? !"checkout_pending".equals(current) : i < consult)) { // ACC-002: no_show only before in_consult
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
    private final OrganizationConfig departmentService;
    private final TenantContext tenantContext;

    private final ClinicClock clock;
    private final com.nabd.hms.common.AuditService auditService;
    private final com.nabd.hms.common.EventPublisher events;

    private final ActorDirectory actors;

    QueueService(QueueRepository repo, AppointmentRepository appointmentRepo, AppointmentService appointmentService,
                 ScheduleRepository scheduleRepo, OrganizationConfig departmentService, TenantContext tenantContext, ClinicClock clock,
                 com.nabd.hms.common.AuditService auditService,
                 com.nabd.hms.common.EventPublisher events, ActorDirectory actors) {
        this.actors = actors;
        this.events = events;
        this.auditService = auditService;
        this.clock = clock;
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
        LocalDate today = clock.today(tenantId);

        if (!repo.patientExists(tenantId, req.patientId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found",
                    "That patient doesn't exist in this clinic.");
        }
        if (!repo.staffExists(tenantId, req.doctorId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found",
                    "That doctor doesn't exist in this clinic.");
        }

        if (req.appointmentId() != null) {
            var appt = appointmentRepo.findById(tenantId, req.appointmentId())
                    .filter(a -> "scheduled".equals(a.status()) || "arrived".equals(a.status()))
                    .filter(a -> a.patientId().equals(req.patientId()) && a.doctorId().equals(req.doctorId()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                            "Appointment not found, already resolved, or doesn't match patient/doctor."));
            if ("arrived".equals(appt.status())) {
                throw alreadyCheckedIn(tenantId, appt.id());
            }
            LocalDate apptDay = appt.startTime().atZone(clock.zone(tenantId)).toLocalDate();
            if (!apptDay.equals(today)) {
                throw new ApiException(HttpStatus.CONFLICT, "appointment-not-today", "Appointment is not for today",
                        "This appointment is dated " + apptDay + ", not today. Book the patient as a walk-in instead.");
            }
        }

        // serializes both "what's the next token number" and, for walk-ins, the session-capacity
        // check below against AppointmentService.book() — same pool, same lock (QueueRepository.lockDoctorDay)
        repo.lockDoctorDay(tenantId, req.doctorId(), today);

        if (req.appointmentId() == null) {
            enforceSessionCapacity(tenantId, req.doctorId(), today); // scheduled check-ins were already capacity-checked at booking
        } else if (appointmentRepo.markArrived(tenantId, req.appointmentId()) == 0) {
            // ACC-003: the flip is atomic under the lock above, so a double-submit (or a second desk) can't mint a second token
            throw alreadyCheckedIn(tenantId, req.appointmentId());
        }

        UUID departmentId = repo.findCheckInDepartment(tenantId, req.doctorId());
        int token = repo.nextTokenNumber(tenantId, req.doctorId(), today);
        String source = req.source() == null ? "walk_in" : req.source();
        // encounterId null -> a fresh visit, defaulted to this row's own id by the DB trigger (V43).
        UUID id;
        try {
            id = repo.insert(tenantId, req.appointmentId(), req.patientId(), req.doctorId(), departmentId,
                    null, null, today, token, source, "checked_in", req.presentingComplaint(), req.paymentMode(), null, null, clock.now());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (req.appointmentId() != null && String.valueOf(e.getMessage()).contains("uq_queue_entries_appointment")) {
                throw new ApiException(HttpStatus.CONFLICT, "already-checked-in", "Already checked in",
                        "This appointment has already been checked in.", java.util.Map.of("code", "ALREADY_CHECKED_IN")); // DB backstop of the lock; tx is aborted, no lookup
            }
            throw e;
        }
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "queue.check_in", "queue_entry", id, null,
                java.util.Map.of("doctorId", req.doctorId(), "tokenNumber", token, "source", source,
                        "appointmentId", req.appointmentId() == null ? "" : req.appointmentId().toString()));
        log.info("queue check-in by {}: token {} for doctor {} ({}), entry {}",  // log-phi-ok: queue token number, not a credential
                callerStaffId, token, req.doctorId(), req.appointmentId() == null ? "walk-in" : "scheduled", id);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public List<QueueEntryResponse> list(UUID tenantId, UUID doctorId, UUID departmentId, LocalDate date, boolean priorityOnly) {
        tenantContext.set(tenantId);
        LocalDate day = date == null ? clock.today(tenantId) : date;
        return repo.listForDay(tenantId, doctorId, departmentId, day, priorityOnly).stream().map(this::toResponse).toList();
    }

    @Transactional
    public QueueEntryResponse updateStatus(UUID tenantId, UUID callerStaffId, UUID id, QueueStatusUpdateRequest req) {
        if ("completed".equals(req.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "completion-reserved-for-billing", "Completion is reserved for billing",
                    "A visit is completed only by a successful checkout.");
        }
        return move(tenantId, callerStaffId, id, req.expectedStatus(), req.status());
    }

    /** Billing's only way to reach 'completed' (see VisitCommands); same CAS and legality as any move. */
    QueueEntryResponse move(UUID tenantId, UUID callerStaffId, UUID id, String expectedStatus, String nextStatus) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (expectedStatus != null && !expectedStatus.equals(current.status())) {
            throw stale(current.status());
        }
        requireLegalTransition(tenantId, current, nextStatus, callerStaffId, id);
        casStatus(tenantId, callerStaffId, current, nextStatus);
        if ("completed".equals(nextStatus) || "no_show".equals(nextStatus)) {
            // a transferred leg has no appointment of its own: the encounter's first leg does (ACC-003, QA-08)
            UUID apptId = current.appointmentId() != null ? current.appointmentId()
                    : repo.appointmentOfEncounter(tenantId, current.encounterId()).orElse(null);
            if (apptId != null) {
                appointmentService.markTerminal(tenantId, apptId, nextStatus);
            }
        }
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** The one status write: WHERE status = the row we validated against, so a concurrent move makes this 0 rows -> 409. */
    private void casStatus(UUID tenantId, UUID callerStaffId, QueueEntryRow current, String nextStatus) {
        if (repo.updateStatus(tenantId, current.id(), current.status(), nextStatus) == 0) {
            throw stale(repo.findById(tenantId, current.id()).map(QueueEntryRow::status).orElseThrow(this::notFound));
        }
        log.info("queue entry {} moved {} -> {} by {}", current.id(), current.status(), nextStatus, callerStaffId);
        String role = actors.findActor(tenantId, callerStaffId).orElseThrow().role();
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("tenantId", tenantId);
        payload.put("queueEntryId", current.id());
        payload.put("encounterId", current.encounterId());
        payload.put("doctorId", current.doctorId());
        payload.put("clinicDate", current.queueDate());
        payload.put("fromStatus", current.status());
        payload.put("toStatus", nextStatus);
        payload.put("occurredAt", clock.now());
        payload.put("actorRole", role);
        events.publish(tenantId, "visit.queue_entry.status_changed.v1", payload);
    }

    private ApiException stale(String currentStatus) {
        return new ApiException(HttpStatus.CONFLICT, "stale-status", "Visit already moved",
                "This visit is no longer at the stage your screen shows.", java.util.Map.of("currentStatus", currentStatus));
    }

    private void requireLegalTransition(UUID tenantId, QueueEntryRow current, String nextStatus, UUID callerStaffId, UUID id) {
        List<String> sequence = departmentService.resolveStatusSequence(tenantId, current.departmentId());
        Set<String> allowed = allowedNextStatuses(current.status(), sequence);
        if (!allowed.contains(nextStatus)) {
            log.warn("illegal queue transition blocked by {}: entry {} {} -> {}", callerStaffId, id, current.status(), nextStatus); // ids only, no PHI
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

        if (req.reason() == null && !req.toDepartmentId().equals(current.departmentId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "transfer-reason-required", "Transfer reason required",
                    "A reason is required when moving a patient to another department.");
        }
        casStatus(tenantId, callerStaffId, current, "transferred_out");
        log.info("queue entry {} transferred_out by {} (patient {} -> department {})",
                id, callerStaffId, current.patientId(), req.toDepartmentId());

        LocalDate today = clock.today(tenantId);
        repo.lockDoctorDay(tenantId, req.doctorId(), today);
        int token = repo.nextTokenNumber(tenantId, req.doctorId(), today);
        // Same encounter, new leg — the whole visit's identity carries across the transfer even
        // though this is a brand-new queue_entries row with its own status/practitioner/department.
        UUID newId = repo.insert(tenantId, null, current.patientId(), req.doctorId(), req.toDepartmentId(),
                current.id(), current.encounterId(), today, token, "internal_transfer", "waiting", null, null, req.reason(), blankToNull(req.reasonNote()), clock.now());
        log.info("queue entry {} opened by transfer from {} (doctor {}, department {})",
                newId, id, req.doctorId(), req.toDepartmentId());

        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "queue.transfer", "queue_entry", newId, java.util.Map.of("departmentId", current.departmentId(), "fromEntryId", id),
                java.util.Map.of("departmentId", req.toDepartmentId(), "doctorId", req.doctorId(),
                        "reason", req.reason() == null ? "" : req.reason(), "reasonNote", blankToNull(req.reasonNote()) == null ? "" : req.reasonNote().trim()));
        return new TransferResponse(
                toResponse(repo.findById(tenantId, id).orElseThrow()),
                toResponse(repo.findById(tenantId, newId).orElseThrow()));
    }

    @Transactional
    public QueueEntryResponse reorder(UUID tenantId, UUID callerStaffId, UUID id, QueueReorderRequest req) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, id).orElseThrow(this::notFound);
        repo.updatePriority(tenantId, id, req.priority(), req.priority() ? req.reason() : null, callerStaffId);
        log.info("queue entry {} priority set to {} by {}", id, req.priority(), callerStaffId);
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
        int patientsAhead = repo.countActiveAhead(tenantId, doctorId, clock.today(tenantId));
        int estimatedMinutes = (int) Math.round(avgVisitMinutes * patientsAhead);
        return new WaitEstimateResponse(estimatedMinutes, patientsAhead, avgVisitMinutes, avg.isPresent());
    }

    /** NB-098 — same session cap AppointmentService.book() enforces, applied to the walk-in path. */
    private void enforceSessionCapacity(UUID tenantId, UUID doctorId, LocalDate today) {
        ZoneId zone = clock.zone(tenantId);
        LocalTime now = clock.now().atZone(zone).toLocalTime(); // the clinic's clock, same as the block times
        int dayOfWeek = today.getDayOfWeek().getValue() % 7;

        Optional<WorkingHoursRow> block = scheduleRepo.findBlockCovering(doctorId, dayOfWeek, now);
        if (block.isEmpty() || block.get().maxPatients() == null) {
            return;
        }

        int occupancy = scheduleRepo.countSessionOccupancy(tenantId, doctorId, today, block.get().startTime(), block.get().endTime(), zone);
        if (occupancy >= block.get().maxPatients()) {
            log.warn("session capacity reached (walk-in check-in): doctor {} on {} ({}/{})",
                    doctorId, today, occupancy, block.get().maxPatients());
            throw sessionFull();
        }
    }

    private QueueEntryResponse toResponse(QueueEntryRow row) {
        return new QueueEntryResponse(row.id(), row.appointmentId(), row.patientId(), row.doctorId(),
                row.departmentId(), row.parentQueueEntryId(), row.encounterId(), row.encounterClass(), row.currentStage(),
                row.workflowDefinitionId(), row.queueDate(), row.tokenNumber(), row.status(),
                row.priority(), row.priorityReason(), row.priorityFlaggedBy(), row.priorityFlaggedAt(),
                row.priorityAcknowledgedBy(), row.priorityAcknowledgedAt(), row.source(),
                row.presentingComplaint(), row.paymentMode(), row.createdAt(),
                row.transferReason(), row.transferReasonNote(), row.ageYears(), row.gender(), row.doctorName(),
                row.waitMinutes(), row.visitType(), row.appointmentStatus());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** 409 with the existing token so the desk can say "Already checked in as T-12 at 10:04". */
    private ApiException alreadyCheckedIn(UUID tenantId, UUID appointmentId) {
        var extras = new java.util.HashMap<String, Object>(repo.findCheckInOf(tenantId, appointmentId).orElse(java.util.Map.of()));
        extras.put("code", "ALREADY_CHECKED_IN");
        return new ApiException(HttpStatus.CONFLICT, "already-checked-in", "Already checked in",
                "This appointment has already been checked in.", extras);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException sessionFull() {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                "This session has reached its patient cap (walk-ins and bookings counted together).");
    }
}
