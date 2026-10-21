package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.queue.dto.DoctorSessionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** ACC-015: a doctor's day is in / paused / out. Every change is audited and published (scheduling.doctor_status.changed.v1). */
@Service
public class DoctorSessionService {

    private final DoctorSessionRepository repo;
    private final QueueRepository queueRepo;
    private final ClinicClock clock;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;

    DoctorSessionService(DoctorSessionRepository repo, QueueRepository queueRepo, ClinicClock clock,
                         AuditService audit, EventPublisher events, ActorDirectory actors) {
        this.repo = repo;
        this.queueRepo = queueRepo;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
    }

    @Transactional
    public DoctorSessionResponse act(UUID tenantId, UUID callerStaffId, UUID doctorId, String action, String reasonCode) {
        if (!queueRepo.staffExists(tenantId, doctorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found", "That doctor doesn't exist in this clinic.");
        }
        Instant now = clock.now();
        LocalDate day = clock.today(tenantId);
        var open = repo.lockOpen(tenantId, doctorId, day);
        switch (action) {
            case "start" -> {
                if (open.isPresent() || !repo.open(tenantId, doctorId, day, now)) throw invalid(open.map(s -> s.state()).orElse("in"), action);
            }
            case "pause" -> {
                if (open.isEmpty() || !"in".equals(open.get().state())) throw invalid(open.map(s -> s.state()).orElse("out"), action);
                repo.pause(tenantId, open.get().id(), now, reasonCode);
            }
            case "resume" -> {
                if (open.isEmpty() || !"paused".equals(open.get().state())) throw invalid(open.map(s -> s.state()).orElse("out"), action);
                repo.resume(tenantId, open.get().id(), now);
            }
            case "end" -> {
                if (open.isEmpty()) throw invalid("out", action);
                repo.end(tenantId, open.get().id(), now);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-session-action", "Unknown session action",
                    "Action must be start, pause, resume or end.");
        }
        var s = repo.latest(tenantId, doctorId, day).orElseThrow();
        publish(tenantId, doctorId, s.state());
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "doctor_session." + action,
                "doctor_session", s.id(), null, Map.of("doctorId", doctorId, "state", s.state()));
        return new DoctorSessionResponse(s.id(), s.state(), s.since());
    }

    @Transactional
    public DoctorSessionResponse today(UUID tenantId, UUID doctorId) {
        return repo.latest(tenantId, doctorId, clock.today(tenantId))
                .map(s -> new DoctorSessionResponse(s.id(), s.state(), s.since()))
                .orElse(new DoctorSessionResponse(null, "out", null));
    }

    /** The first in_consult of the day starts the session if the doctor forgot. Runs in the move's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void startIfNone(UUID tenantId, UUID doctorId, Instant at) {
        LocalDate day = clock.today(tenantId);
        if (repo.open(tenantId, doctorId, day, at)) publish(tenantId, doctorId, "in");
    }

    private void publish(UUID tenantId, UUID doctorId, String state) {
        events.publish(tenantId, DomainEventType.DOCTOR_STATUS_CHANGED, doctorId,
                Map.of("doctorId", doctorId, "status", state)); // the envelope time is the change time
    }

    private ApiException invalid(String state, String action) {
        return new ApiException(HttpStatus.CONFLICT, "invalid-session-state", "Invalid session state",
                "Cannot " + action + " a session that is " + state + ".", Map.of("code", "INVALID_SESSION_STATE", "currentState", state));
    }
}
