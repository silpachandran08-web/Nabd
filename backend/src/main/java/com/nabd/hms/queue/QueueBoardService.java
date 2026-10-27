package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DisplayNames;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.queue.QueueModels.QueueEntryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-022: the one-call board projection and the reassign of a waiting patient to another doctor of the same department. */
@Service
class QueueBoardService {

    private static final List<String> AFTER_CONSULT = List.of("in_consult", "checkout_pending");

    @Schema(name = "QueueBoardEntry")
    record Entry(UUID id, UUID patientId, int token, Integer previousToken, String displayName, String mrn, String stage, int position,
                 Instant p50At, Instant p80At, boolean priority, List<String> flags, boolean allergyFlag, boolean vitalsRecorded) {
    }

    @Schema(name = "QueueBoardDoctor")
    record Doctor(UUID doctorId, String name, String state, Integer delayMinutes, Integer nowServing, List<Entry> entries) {
    }

    @Schema(name = "QueueBoard")
    record Board(long v, LocalDate date, List<Doctor> doctors) {
    }

    @Schema(name = "QueueReassignResponse")
    record Reassigned(com.nabd.hms.queue.dto.QueueEntryResponse entry, int previousToken) {
    }

    private final QueueBoardRepository repo;
    private final QueueRepository queueRepo;
    private final QueueService queues;
    private final ScheduleRepository scheduleRepo;
    private final QueueEventRepository eventRepo;
    private final OrganizationConfig departments;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;

    QueueBoardService(QueueBoardRepository repo, QueueRepository queueRepo, QueueService queues, ScheduleRepository scheduleRepo,
                      QueueEventRepository eventRepo, OrganizationConfig departments, TenantContext tenantContext, ClinicClock clock,
                      AuditService audit, EventPublisher events, ActorDirectory actors) {
        this.repo = repo;
        this.queueRepo = queueRepo;
        this.queues = queues;
        this.scheduleRepo = scheduleRepo;
        this.eventRepo = eventRepo;
        this.departments = departments;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
    }

    @Transactional
    public Board board(LocalDate date, UUID departmentId) {
        UUID tenantId = tenantContext.current();
        LocalDate day = date == null ? clock.clinicDate(tenantId) : date;
        var doctors = repo.doctors(tenantId, day, departmentId);
        var byDoctor = new LinkedHashMap<UUID, List<QueueBoardRepository.EntryRow>>();
        repo.entries(tenantId, day, departmentId).forEach(e -> byDoctor.computeIfAbsent(e.doctorId(), k -> new ArrayList<>()).add(e));
        var states = repo.sessionStates(tenantId, day);
        var delays = repo.activeDelays(tenantId, day);
        List<Doctor> out = new ArrayList<>();
        for (var d : doctors) {
            List<Entry> entries = new ArrayList<>();
            Integer nowServing = null;
            int position = 0;
            for (var e : byDoctor.getOrDefault(d.id(), List.of())) {
                boolean waiting = !AFTER_CONSULT.contains(e.status());
                if ("in_consult".equals(e.status()) && nowServing == null) {
                    nowServing = e.token();
                }
                List<String> flags = new ArrayList<>();
                flags.add(e.source());
                if (e.previousToken() != null) {
                    flags.add("reassigned");
                }
                entries.add(new Entry(e.id(), e.patientId(), e.token(), e.previousToken(), DisplayNames.firstNameLastInitial(e.patientName()),
                        e.mrn(), e.status(), waiting ? ++position : 0, e.p50At(), e.p80At(), e.priority(), flags, e.allergy(), e.vitals()));
            }
            out.add(new Doctor(d.id(), d.name(), states.getOrDefault(d.id(), "out"), delays.get(d.id()), nowServing, entries));
        }
        return new Board(clock.now().toEpochMilli(), day, out);
    }

    @Transactional
    public Reassigned reassign(UUID callerStaffId, UUID id, UUID toDoctorId, String expectedStatus, String reasonCode) {
        UUID tenantId = tenantContext.current();
        QueueEntryRow first = queueRepo.findById(tenantId, id).orElseThrow(this::notFound);
        if (toDoctorId.equals(first.doctorId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Pick a different doctor.");
        }
        var toDepartment = queueRepo.findCheckInDepartment(tenantId, toDoctorId);
        if (toDepartment.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found", "That doctor doesn't exist in this clinic.");
        }
        if (!first.departmentId().equals(toDepartment.get(0))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "different-department", "Different department",
                    "Reassign stays inside one department; use transfer to move a patient to another.", Map.of("code", "DIFFERENT_DEPARTMENT"));
        }
        LocalDate today = first.queueDate();
        // both doctors' token pools, always in id order so two desks moving opposite ways cannot deadlock
        UUID a = first.doctorId().compareTo(toDoctorId) < 0 ? first.doctorId() : toDoctorId;
        UUID b = a.equals(first.doctorId()) ? toDoctorId : first.doctorId();
        queueRepo.lockDoctorDay(tenantId, a, today);
        queueRepo.lockDoctorDay(tenantId, b, today);

        QueueEntryRow current = queueRepo.findById(tenantId, id).orElseThrow(this::notFound);
        if (!current.doctorId().equals(first.doctorId()) || !current.status().equals(expectedStatus) || !reassignable(tenantId, current)) {
            throw stale(current.status());
        }
        enforceSessionCapacity(tenantId, toDoctorId, today);
        int newToken = queueRepo.nextTokenNumber(tenantId, toDoctorId, today);
        if (repo.reassign(tenantId, id, expectedStatus, current.doctorId(), toDoctorId, newToken) == 0) {
            throw stale(queueRepo.findById(tenantId, id).map(QueueEntryRow::status).orElseThrow(this::notFound));
        }
        eventRepo.record(tenantId, id, current.status(), current.status(), "reassign", null, clock.now(), callerStaffId,
                callerStaffId.equals(current.doctorId()) || callerStaffId.equals(toDoctorId) ? "doctor" : "desk");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("entryId", id);
        payload.put("doctorId", toDoctorId);
        payload.put("queueDate", today);
        payload.put("status", current.status());
        payload.put("fromDoctorId", current.doctorId());
        payload.put("newToken", newToken);
        payload.put("previousToken", current.tokenNumber());
        events.publish(tenantId, DomainEventType.QUEUE_ENTRY_CHANGED, id, payload);
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "queue.reassign", "queue_entry", id,
                Map.of("doctorId", current.doctorId(), "tokenNumber", current.tokenNumber()),
                Map.of("doctorId", toDoctorId, "tokenNumber", newToken, "reasonCode", reasonCode));
        return new Reassigned(queues.view(tenantId, id), current.tokenNumber());
    }

    /** Only before the consult starts: the stage must sit ahead of in_consult in the department's pipeline. */
    private boolean reassignable(UUID tenantId, QueueEntryRow row) {
        List<String> sequence = departments.resolveStatusSequence(tenantId, row.departmentId());
        int at = sequence.indexOf(row.status());
        int consult = sequence.indexOf("in_consult");
        return at >= 0 && (consult < 0 ? at < sequence.size() - 1 : at < consult);
    }

    /** Same session cap a walk-in check-in meets (NB-098), applied to the target doctor. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always inside the caller's tx
    private void enforceSessionCapacity(UUID tenantId, UUID doctorId, LocalDate today) {
        var block = scheduleRepo.blockAt(tenantId, doctorId, clock.now()); // ACC-005: today is the clinic date
        if (block.isPresent() && block.get().maxPatients() != null
                && scheduleRepo.countSessionOccupancy(tenantId, doctorId, today, block.get()) >= block.get().maxPatients()) {
            throw new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                    "That doctor's session has reached its patient cap.", Map.of("code", "SESSION_FULL"));
        }
    }

    private ApiException stale(String currentStatus) {
        return new ApiException(HttpStatus.CONFLICT, "stale-status", "Visit already moved",
                "This visit is no longer at the stage your screen shows.", Map.of("currentStatus", currentStatus, "code", "STALE_STATUS"));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
