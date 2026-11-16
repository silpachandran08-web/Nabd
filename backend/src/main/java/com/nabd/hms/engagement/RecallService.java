package com.nabd.hms.engagement;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.engagement.api.RecallBookings;
import com.nabd.hms.engagement.dto.RecallOrderRequest;
import com.nabd.hms.engagement.dto.RecallOrderResponse;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.notify.CancelReason;
import com.nabd.hms.shared.notify.NotificationPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-052: a recall order set at consult close ("Review in 2 weeks"): due = visit date (clinic calendar) + intervalDays.
 * Booking the follow-up marks it 'booked' and cancels any reminder requested about it (subject "recall_order"), so the
 * due-recall sender (COM-054) only ever messages 'pending' rows.
 */
@Service
public class RecallService implements RecallBookings {

    static final String SUBJECT = "recall_order";
    static final int MAX_INTERVAL_DAYS = 730;

    private final RecallRepository repo;
    private final ClinicClock clock;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;
    private final NotificationPort notifications;

    RecallService(RecallRepository repo, ClinicClock clock, AuditService audit, EventPublisher events, ActorDirectory actors,
                  NotificationPort notifications) {
        this.repo = repo;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
        this.notifications = notifications;
    }

    @Transactional
    public RecallOrderResponse create(UUID tenantId, UUID staffId, UUID encounterId, RecallOrderRequest req) {
        RecallRepository.Encounter e = repo.encounter(tenantId, encounterId).orElseThrow(ApiException::notFound);
        if (!staffId.equals(e.doctorId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-doctor-of-record", "Not the visit doctor",
                    "Only the doctor of this visit can set its recall.", Map.of("code", "NOT_DOCTOR_OF_RECORD"));
        }
        Integer days = req.intervalDays();
        if (days == null || days < 1 || days > MAX_INTERVAL_DAYS) { // rule-based due dates arrive with the recall rules (COM-053)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "interval-out-of-range", "Interval out of range",
                    "Pick a recall interval between 1 and " + MAX_INTERVAL_DAYS + " days.", Map.of("code", "INTERVAL_OUT_OF_RANGE"));
        }
        LocalDate visit = e.visitDate() != null ? e.visitDate() : clock.today(tenantId);
        LocalDate due = visit.plusDays(days);
        Optional<UUID> booked = repo.upcomingFollowUp(tenantId, e.patientId(), e.doctorId(), clock.now());
        String status = booked.isPresent() ? "booked" : "pending";
        UUID id;
        try {
            id = repo.insert(tenantId, e.patientId(), encounterId, e.doctorId(), req.ruleId(), req.reasonCode(), days, due, status,
                    booked.orElse(null), staffId);
        } catch (DuplicateKeyException dup) {
            throw new ApiException(HttpStatus.CONFLICT, "recall-exists-for-encounter", "Recall already set",
                    "This visit already has a recall.", Map.of("code", "RECALL_EXISTS_FOR_ENCOUNTER"));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("encounterId", encounterId.toString());
        after.put("intervalDays", days);
        after.put("dueDate", due.toString());
        after.put("status", status);
        if (req.reasonCode() != null) after.put("reasonCode", req.reasonCode());
        record(tenantId, staffId, "recall_order.created", id, null, after);
        events.publish(tenantId, DomainEventType.RECALL_CREATED, id,
                Map.of("recallId", id.toString(), "patientId", e.patientId().toString(), "dueDate", due.toString()));
        return repo.find(tenantId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<RecallOrderResponse> forEncounter(UUID tenantId, UUID encounterId) {
        repo.encounter(tenantId, encounterId).orElseThrow(ApiException::notFound);
        return repo.forEncounter(tenantId, encounterId);
    }

    @Override
    @Transactional
    public void followUpBooked(UUID tenantId, UUID callerStaffId, UUID patientId, UUID doctorId, UUID appointmentId) {
        repo.markBooked(tenantId, patientId, doctorId, appointmentId).ifPresent(id -> {
            notifications.cancelBySubject(SUBJECT, id, CancelReason.SUBJECT_CHANGED);
            record(tenantId, callerStaffId, "recall_order.booked", id, null, Map.of("status", "booked", "appointmentId", appointmentId.toString()));
        });
    }

    private void record(UUID tenantId, UUID staffId, String action, UUID id, Object before, Object after) {
        ActorDirectory.Actor actor = staffId == null ? null : actors.findActor(tenantId, staffId).orElse(null);
        audit.record(tenantId, actor == null ? "system" : "staff", actor == null ? null : staffId, actor == null ? "System" : actor.name(),
                actor == null ? "System" : actor.role(), null, action, SUBJECT, id, before, after);
    }
}
