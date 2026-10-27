package com.nabd.hms.queue;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
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
    private final QueueEventRepository eventRepo;
    private final DoctorSessionService sessions;
    private final EtaService eta;
    private final ServiceTimeService serviceTimes;
    private final com.nabd.hms.patient.api.ProxyAccess proxy; // IDC-011

    QueueService(QueueRepository repo, AppointmentRepository appointmentRepo, AppointmentService appointmentService,
                 ScheduleRepository scheduleRepo, OrganizationConfig departmentService, TenantContext tenantContext, ClinicClock clock,
                 com.nabd.hms.common.AuditService auditService,
                 com.nabd.hms.common.EventPublisher events, ActorDirectory actors,
                 QueueEventRepository eventRepo, DoctorSessionService sessions, EtaService eta, ServiceTimeService serviceTimes, com.nabd.hms.patient.api.ProxyAccess proxy) {
        this.proxy = proxy;
        this.eta = eta;
        this.serviceTimes = serviceTimes;
        this.eventRepo = eventRepo;
        this.sessions = sessions;
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
        var by = req.actedByPatientId() == null ? null : proxy.onBehalf(req.actedByPatientId(), req.actedByRelation(), req.patientId()); // IDC-011
        var doctorDepartment = repo.findCheckInDepartment(tenantId, req.doctorId()); // also the doctor-exists check
        if (doctorDepartment.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found",
                    "That doctor doesn't exist in this clinic.");
        }

        Integer reservedToken = null; // ACC-011: the number a token booking reserved
        Instant lateAfterGrace = null; // ACC-012: slot start + the session's grace
        if (req.appointmentId() != null) {
            var appt = appointmentRepo.findById(tenantId, req.appointmentId())
                    .filter(a -> "scheduled".equals(a.status()) || "arrived".equals(a.status()))
                    .filter(a -> a.patientId().equals(req.patientId()) && a.doctorId().equals(req.doctorId()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                            "Appointment not found, already resolved, or doesn't match patient/doctor."));
            if ("arrived".equals(appt.status())) {
                throw alreadyCheckedIn(tenantId, appt.id());
            }
            if ("token".equals(appt.bookingKind())) {
                reservedToken = appt.sessionToken();
            } else {
                lateAfterGrace = scheduleRepo.findBlockCovering(req.doctorId(), apptDayOfWeek(tenantId, appt.startTime()),
                                appt.startTime().atZone(clock.zone(tenantId)).toLocalTime())
                        .map(b -> appt.startTime().plus(java.time.Duration.ofMinutes(b.lateGraceMinutes())))
                        .orElse(appt.startTime().plus(java.time.Duration.ofMinutes(15)));
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

        UUID departmentId = doctorDepartment.get(0);
        // ACC-011: a token booking is issued its reserved number if still free, else the next one
        int token = reservedToken != null && !repo.tokenTaken(tenantId, req.doctorId(), today, reservedToken)
                ? reservedToken : repo.nextTokenNumber(tenantId, req.doctorId(), today);
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
        if (by != null) {
            repo.stampActedBy(tenantId, id, by.patientId(), by.relation());
            proxy.audit(by, "check_in", "queue_entry", id);
        }
        entryCreated(tenantId, id, req.doctorId(), today, "checked_in", callerStaffId);
        if (lateAfterGrace != null && clock.now().isAfter(lateAfterGrace)) { // ACC-012
            repo.markLateArrival(tenantId, id);
        }
        if (req.appointmentId() != null) {
            events.publish(tenantId, DomainEventType.APPOINTMENT_ARRIVED, req.appointmentId(), java.util.Map.of(
                    "appointmentId", req.appointmentId(), "doctorId", req.doctorId(), "queueEntryId", id, "status", "arrived"));
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

    private int apptDayOfWeek(UUID tenantId, Instant start) {
        return start.atZone(clock.zone(tenantId)).toLocalDate().getDayOfWeek().getValue() % 7;
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
        return move(tenantId, callerStaffId, id, expectedStatus, nextStatus, null);
    }

    /** orderReason (ACC-012): why the policy called this entry; carried on the changed event. */
    QueueEntryResponse move(UUID tenantId, UUID callerStaffId, UUID id, String expectedStatus, String nextStatus, String orderReason) {
        tenantContext.set(tenantId);
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (expectedStatus != null && !expectedStatus.equals(current.status())) {
            throw stale(current.status());
        }
        requireLegalTransition(tenantId, current, nextStatus, callerStaffId, id);
        casStatus(tenantId, callerStaffId, current, nextStatus, orderReason);
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
    private void casStatus(UUID tenantId, UUID callerStaffId, QueueEntryRow current, String nextStatus, String orderReason) {
        if (repo.updateStatus(tenantId, current.id(), current.status(), nextStatus) == 0) {
            throw stale(repo.findById(tenantId, current.id()).map(QueueEntryRow::status).orElseThrow(this::notFound));
        }
        log.info("queue entry {} moved {} -> {} by {}", current.id(), current.status(), nextStatus, callerStaffId);
        recordEvent(tenantId, current.id(), current.status(), nextStatus, "transition", null, callerStaffId, current.doctorId());
        if ("in_consult".equals(nextStatus)) {
            sessions.startIfNone(tenantId, current.doctorId(), clock.now()); // ACC-015: the doctor forgot to tap Start
        }
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
        events.publish(tenantId, "visit.queue_entry.status_changed.v1", payload); // deprecated: superseded by visit.queue_entry.changed.v1
        var changed = new java.util.LinkedHashMap<String, Object>();
        changed.put("entryId", current.id());
        changed.put("doctorId", current.doctorId());
        changed.put("queueDate", current.queueDate());
        changed.put("fromStatus", current.status());
        changed.put("status", nextStatus);
        if (orderReason != null) changed.put("orderReason", orderReason);
        events.publish(tenantId, DomainEventType.QUEUE_ENTRY_CHANGED, current.id(), changed);
        if ("completed".equals(nextStatus)) {
            events.publish(tenantId, DomainEventType.VISIT_COMPLETED, current.encounterId(), java.util.Map.of("encounterId", current.encounterId(),
                    "entryId", current.id(), "doctorId", current.doctorId(), "queueDate", current.queueDate(), "status", "completed"));
        }
    }

    /** OPS-015: data is exactly { tenantId, entryId, doctorId, queueDate, status, version } (the publisher adds tenantId/version). */
    private void entryCreated(UUID tenantId, UUID entryId, UUID doctorId, LocalDate queueDate, String status, UUID callerStaffId) {
        recordEvent(tenantId, entryId, null, status, "transition", null, callerStaffId, doctorId);
        events.publish(tenantId, DomainEventType.QUEUE_ENTRY_CREATED, entryId,
                java.util.Map.of("entryId", entryId, "doctorId", doctorId, "queueDate", queueDate, "status", status));
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
        casStatus(tenantId, callerStaffId, current, "transferred_out", null);
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
        entryCreated(tenantId, newId, req.doctorId(), today, "waiting", callerStaffId);

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
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (req.priority() && (req.reason() == null || req.reason().isBlank())) { // ACC-022: a priority flag needs its reason
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                    "State why this patient is moved up.", java.util.Map.of("code", "REASON_REQUIRED"));
        }
        if (req.reason() != null && !req.reason().isBlank() && req.reason().strip().length() < 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "reason: must be at least 3 characters");
        }
        repo.updatePriority(tenantId, id, req.priority(), req.priority() ? req.reason() : null, callerStaffId);
        recordEvent(tenantId, id, current.status(), current.status(), "priority", null, callerStaffId, current.doctorId());
        events.publish(tenantId, DomainEventType.QUEUE_ENTRY_CHANGED, id, java.util.Map.of("entryId", id, "doctorId", current.doctorId(),
                "queueDate", current.queueDate(), "status", current.status(), "priority", req.priority()));
        log.info("queue entry {} priority set to {} by {}", id, req.priority(), callerStaffId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** ACC-015: "Extend by N min" on a running consult; an event the ETA engine reads, and a queue change so desks refresh. */
    @Transactional
    public com.nabd.hms.queue.dto.ConsultExtendResponse extend(UUID tenantId, UUID callerStaffId, UUID id, int minutes) {
        // tenant already bound by TenantTransactionBinder (PLT-019)
        QueueEntryRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if (!"in_consult".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "not-in-consult", "Not in consult",
                    "Only a visit that is in consult can be extended.", java.util.Map.of("code", "NOT_IN_CONSULT", "currentStatus", current.status()));
        }
        recordEvent(tenantId, id, "in_consult", "in_consult", "extend", minutes, callerStaffId, current.doctorId());
        events.publish(tenantId, DomainEventType.QUEUE_ENTRY_CHANGED, id, java.util.Map.of("entryId", id, "doctorId", current.doctorId(),
                "queueDate", current.queueDate(), "status", "in_consult", "extendedMinutes", minutes));
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "queue.extend", "queue_entry", id, null, java.util.Map.of("minutes", minutes));
        return new com.nabd.hms.queue.dto.ConsultExtendResponse(id, minutes);
    }

    /** ACC-015: one queue_entry_events row, in the caller's transaction. The doctor moving their own patient is source 'doctor'. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private void recordEvent(UUID tenantId, UUID entryId, String from, String to, String kind, Integer minutes, UUID callerStaffId, UUID doctorId) {
        eventRepo.record(tenantId, entryId, from, to, kind, minutes, clock.now(), callerStaffId,
                callerStaffId.equals(doctorId) ? "doctor" : "desk");
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

    /** NB-101 / ACC-017: minutes until a patient checking in right now sees this doctor, read from the ETA projection (not computed per call). */
    @Transactional
    public WaitEstimateResponse waitEstimate(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        EtaService.Day d = eta.day(doctorId, clock.today(tenantId));
        var learned = serviceTimes.estimate(doctorId, "new", "default");
        int minutes = d.newcomerP50() == null ? 0 : (int) Math.max(0, java.time.Duration.between(clock.now(), d.newcomerP50()).toMinutes());
        return new WaitEstimateResponse(minutes, (int) d.results().stream().filter(r -> r.entryId() != null).count(),
                learned.dist().medianS() / 60.0, learned.n() > 0);
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

    /** ACC-022: the entry as the API shows it (used by the reassign service in this package). */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    QueueEntryResponse view(UUID tenantId, UUID id) {
        return toResponse(repo.findById(tenantId, id).orElseThrow(this::notFound));
    }

    private QueueEntryResponse toResponse(QueueEntryRow row) {
        return new QueueEntryResponse(row.id(), row.appointmentId(), row.patientId(), row.doctorId(),
                row.departmentId(), row.parentQueueEntryId(), row.encounterId(), row.encounterClass(), row.currentStage(),
                row.workflowDefinitionId(), row.queueDate(), row.tokenNumber(), row.status(),
                row.priority(), row.priorityReason(), row.priorityFlaggedBy(), row.priorityFlaggedAt(),
                row.priorityAcknowledgedBy(), row.priorityAcknowledgedAt(), row.source(),
                row.presentingComplaint(), row.paymentMode(), row.createdAt(),
                row.transferReason(), row.transferReasonNote(), row.ageYears(), row.gender(), row.doctorName(),
                row.waitMinutes(), row.visitType(), row.appointmentStatus(), null, row.orderReason(), null);
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
                "This session has reached its patient cap (walk-ins and bookings counted together).", java.util.Map.of("code", "SESSION_FULL", "scope", "total"));
    }
}
