package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.queue.dto.CallNextResponse;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/** ACC-012: applies {@link CallOrderPolicy} to a doctor's open queue: call-next, and position / reason / expected range for lists. */
@Service
public class CallOrderService {
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final QueueRepository queueRepo;
    private final ScheduleRepository scheduleRepo;
    private final DoctorSessionRepository sessionRepo;
    private final QueueService queueService;
    private final OrganizationConfig departments;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;

    CallOrderService(QueueRepository queueRepo, ScheduleRepository scheduleRepo, DoctorSessionRepository sessionRepo,
                     QueueService queueService, OrganizationConfig departments, TenantContext tenantContext, ClinicClock clock,
                     AuditService audit, ActorDirectory actors) {
        this.queueRepo = queueRepo;
        this.scheduleRepo = scheduleRepo;
        this.sessionRepo = sessionRepo;
        this.queueService = queueService;
        this.departments = departments;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    /** The doctor's open queue in call order, under the session rules covering "now" (defaults when no session covers it). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<CallOrderPolicy.Placed> order(UUID tenantId, UUID doctorId, LocalDate day, ZoneId zone) {
        Instant now = clock.now();
        CallOrderPolicy.Rules rules = scheduleRepo.blockAt(tenantId, doctorId, now)
                .map(b -> rules(b)).orElse(CallOrderPolicy.Rules.defaults());
        return CallOrderPolicy.order(queueRepo.openCandidates(tenantId, doctorId, day), rules.withSince(queueRepo.bookedSinceWalkin(tenantId, doctorId, day)), zone);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private CallOrderPolicy.Rules rules(WorkingHoursRow b) {
        CallOrderPolicy.Mode mode = switch (b.walkinMode()) {
            case "reserved_only" -> CallOrderPolicy.Mode.RESERVED_ONLY;
            case "none" -> CallOrderPolicy.Mode.NONE;
            default -> CallOrderPolicy.Mode.INTERLEAVE;
        };
        return new CallOrderPolicy.Rules(mode, b.walkinRatio(), b.lateGraceMinutes(), scheduleRepo.reservedMinutes(b.id()));
    }

    @Transactional
    public CallNextResponse callNext(UUID callerStaffId, UUID doctorId, UUID expectedHeadId) {
        UUID tenantId = tenantContext.current(); // bound at transaction start (PLT-019)
        if (!queueRepo.staffExists(tenantId, doctorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found", "That doctor doesn't exist in this clinic.");
        }
        LocalDate day = clock.clinicDate(tenantId);
        if (sessionRepo.latest(tenantId, doctorId, day).map(s -> "paused".equals(s.state())).orElse(false)) {
            throw conflict("queue-paused", "QUEUE_PAUSED", "Queue paused", "The doctor has paused the queue.");
        }
        queueRepo.lockDoctorDay(tenantId, doctorId, day); // two taps / two desks serialise here; the status CAS below is the backstop
        var waiting = queueRepo.listForDay(tenantId, doctorId, null, day, false).stream()
                .filter(e -> "waiting".equals(e.status())).map(e -> e.id()).collect(java.util.stream.Collectors.toSet());
        var head = order(tenantId, doctorId, day, clock.zone(tenantId)).stream().filter(p -> waiting.contains(p.id())).findFirst()
                .orElseThrow(() -> conflict("queue-empty", "QUEUE_EMPTY", "Queue empty", "Nobody is waiting for this doctor."));
        if (expectedHeadId != null && !expectedHeadId.equals(head.id())) {
            throw conflict("stale-head", "STALE_HEAD", "Queue changed", "The next patient is no longer the one your screen shows.");
        }
        var entry = queueRepo.findById(tenantId, head.id()).orElseThrow();
        List<String> sequence = departments.resolveStatusSequence(tenantId, entry.departmentId());
        int at = sequence.indexOf("waiting");
        String next = at >= 0 && at + 1 < sequence.size() ? sequence.get(at + 1) : "in_consult";
        QueueEntryResponse moved = queueService.move(tenantId, callerStaffId, head.id(), "waiting", next, head.reason());
        String shown = head.lateAsWalkin() && !"priority".equals(head.reason()) ? CallOrderPolicy.LATE_TEXT : head.reason();
        queueRepo.markCalled(tenantId, doctorId, day, head.id(), shown);
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "queue.call_next", "queue_entry", head.id(), null,
                Map.of("doctorId", doctorId, "reason", head.reason())); // priority calls are the audited override
        return new CallNextResponse(withPlacement(moved, null, shown, null), head.reason());
    }

    /** Position (1 = next), why-here and an expected range for each still-open entry of each doctor-day; other entries come back unchanged. */
    @Transactional
    public List<QueueEntryResponse> annotate(List<QueueEntryResponse> entries) {
        return entries.isEmpty() ? entries : annotate(tenantContext.current(), entries);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<QueueEntryResponse> annotate(UUID tenantId, List<QueueEntryResponse> entries) {
        ZoneId zone = clock.zone(tenantId);
        Instant now = clock.now();
        Map<UUID, CallOrderPolicy.Placed> byId = new HashMap<>();
        Map<UUID, Integer> pos = new HashMap<>();
        Map<UUID, Double> avgByDoctor = new HashMap<>();
        for (var key : entries.stream().map(e -> List.of(e.doctorId(), e.queueDate())).distinct().toList()) {
            UUID doctorId = (UUID) key.get(0);
            var placed = order(tenantId, doctorId, (LocalDate) key.get(1), zone);
            for (int i = 0; i < placed.size(); i++) {
                pos.put(placed.get(i).id(), i + 1);
                byId.put(placed.get(i).id(), placed.get(i));
            }
            if (!placed.isEmpty()) avgByDoctor.put(doctorId, queueRepo.averageRecentVisitMinutes(tenantId, doctorId, 30).orElse(15.0));
        }
        return entries.stream().map(e -> {
            Integer p = pos.get(e.id());
            if (p == null) return e;
            var pl = byId.get(e.id());
            String why = pl.lateAsWalkin() && !"priority".equals(pl.reason()) ? CallOrderPolicy.LATE_TEXT : pl.reason();
            return withPlacement(e, p, why, range(now, p - 1, avgByDoctor.get(e.doctorId()), zone));
        }).toList();
    }

    /** Check-in and its placement annotation in ONE transaction (one tenant bind, OPS-020 statement budget). */
    @Transactional
    public QueueEntryResponse checkInAnnotated(UUID tenantId, UUID callerStaffId, com.nabd.hms.queue.dto.CheckInRequest req) {
        return annotate(tenantId, List.of(queueService.checkIn(tenantId, callerStaffId, req))).get(0);
    }

    /** Stand-in until the ETA engine (ACC-017) supplies p50/p80: patients ahead x the doctor's recent average consult. */
    private static String range(Instant now, int ahead, double avgMinutes, ZoneId zone) {
        long lo = Math.round(ahead * avgMinutes * 0.8 / 5.0) * 5, hi = Math.round((ahead * avgMinutes * 1.2 + avgMinutes) / 5.0) * 5;
        return now.plus(Duration.ofMinutes(lo)).atZone(zone).format(HHMM) + "-" + now.plus(Duration.ofMinutes(Math.max(hi, lo + 5))).atZone(zone).format(HHMM);
    }

    private static QueueEntryResponse withPlacement(QueueEntryResponse e, Integer position, String reason, String range) {
        return new QueueEntryResponse(e.id(), e.appointmentId(), e.patientId(), e.doctorId(), e.departmentId(), e.parentQueueEntryId(),
                e.encounterId(), e.encounterClass(), e.currentStage(), e.workflowDefinitionId(), e.queueDate(), e.tokenNumber(), e.status(),
                e.priority(), e.priorityReason(), e.priorityFlaggedBy(), e.priorityFlaggedAt(), e.priorityAcknowledgedBy(),
                e.priorityAcknowledgedAt(), e.source(), e.presentingComplaint(), e.paymentMode(), e.createdAt(), e.transferReason(),
                e.transferReasonNote(), e.ageYears(), e.gender(), e.doctorName(), e.waitMinutes(), e.visitType(), e.appointmentStatus(),
                position, reason, range);
    }

    private static ApiException conflict(String slug, String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, slug, title, detail, Map.of("code", code));
    }
}
