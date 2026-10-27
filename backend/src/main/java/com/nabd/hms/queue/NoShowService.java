package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.BookingRules;
import com.nabd.hms.queue.api.ReasonCodes;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;

/**
 * ACC-025: a booking nobody arrived for becomes no_show, by staff (POST /appointments/{id}/no-show) or by the minute job once
 * start + the policy's grace has passed. Staff may undo within the same clinic day. A patient who turns up afterwards is
 * checked in as a walk-in linked to the booking (late arrival). Every mark asks for an ETA recompute so the queue pulls forward.
 * shortcut: the auto-mark covers today's slot bookings only (token bookings and earlier days are marked by staff), and the
 * "told only if earlier by 10 min" message to the patients behind is ACC-044's hysteresis, not built here.
 */
@Service
class NoShowService {

    private static final Logger log = LoggerFactory.getLogger(NoShowService.class);

    private final AppointmentRepository repo;
    private final AppointmentService appointments;
    private final QueueService queue;
    private final QueueRepository queueRepo;
    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ReasonCodes reasons;
    private final EtaTrigger eta;
    private final BookingRules rules;
    private final com.nabd.hms.shared.notify.NotificationPort notifications;

    NoShowService(AppointmentRepository repo, AppointmentService appointments, QueueService queue, QueueRepository queueRepo,
                  JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, EventPublisher events, AuditService audit,
                  ActorDirectory actors, ReasonCodes reasons, EtaTrigger eta, BookingRules rules,
                  com.nabd.hms.shared.notify.NotificationPort notifications) {
        this.repo = repo;
        this.appointments = appointments;
        this.queue = queue;
        this.queueRepo = queueRepo;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
        this.reasons = reasons;
        this.eta = eta;
        this.rules = rules;
        this.notifications = notifications;
    }

    @Transactional
    public AppointmentResponse mark(UUID staffId, UUID id, String reasonCode) {
        UUID tenant = tenantContext.current();
        AppointmentRow a = repo.findById(tenant, id).orElseThrow(NoShowService::notFound);
        if (reasonCode != null) {
            reasons.requireKnown("no_show", reasonCode);
        }
        if ("no_show".equals(a.status())) {
            return appointments.get(tenant, id); // repeat of the same action: nothing to do
        }
        if ("arrived".equals(a.status())) {
            throw conflict("appointment-arrived", "Appointment already arrived", "The patient has checked in; it cannot be marked as a no-show.", "APPOINTMENT_ARRIVED");
        }
        if (!"scheduled".equals(a.status())) {
            throw conflict("not-scheduled", "Appointment is not scheduled", "Only a scheduled appointment can be marked as a no-show.", "NOT_SCHEDULED");
        }
        Instant due = "token".equals(a.bookingKind()) ? a.endTime() : a.startTime();
        if (clock.now().isBefore(due)) {
            throw conflict("not-yet-due", "Not yet due", "The appointment time has not passed yet.", "NOT_YET_DUE");
        }
        int n = jdbc.update("UPDATE appointments SET status = 'no_show', no_show_at = ?, no_show_marked_by = ?, no_show_reason_code = ? "
                + "WHERE tenant_id = ? AND id = ? AND status = 'scheduled'", Timestamp.from(clock.now()), staffId, reasonCode, tenant, id);
        if (n == 0) {
            throw conflict("stale-status", "Appointment already changed", "The appointment changed while you were looking at it.", "STALE_STATUS");
        }
        marked(tenant, a, staffId);
        var actor = actors.findActor(tenant, staffId).orElseThrow();
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "appointment.no_show_marked", "appointment", id,
                Map.of("status", "scheduled"), reasonCode == null ? Map.of("status", "no_show") : Map.of("status", "no_show", "reasonCode", reasonCode));
        log.info("appointment {} marked no-show by {}", id, staffId);
        return appointments.get(tenant, id);
    }

    @Transactional
    public AppointmentResponse undo(UUID staffId, UUID id) {
        UUID tenant = tenantContext.current();
        AppointmentRow a = repo.findById(tenant, id).orElseThrow(NoShowService::notFound);
        if (!"no_show".equals(a.status()) || a.noShowAt() == null) {
            throw conflict("not-no-show", "Not a marked no-show", "Only an appointment marked as a no-show can be undone.", "NOT_NO_SHOW");
        }
        ZoneId zone = clock.zone(tenant);
        if (!a.noShowAt().atZone(zone).toLocalDate().equals(clock.today(zone))) {
            throw conflict("undo-window-passed", "Undo window passed", "A no-show can be undone only on the clinic day it was marked.", "UNDO_WINDOW_PASSED");
        }
        if (a.lateArrivalQueueEntryId() != null) {
            throw conflict("late-arrival-linked", "Patient already checked in", "The patient was checked in through late arrival; the no-show cannot be undone.", "LATE_ARRIVAL_LINKED");
        }
        try {
            jdbc.update("UPDATE appointments SET status = 'scheduled', no_show_at = NULL, no_show_reason_code = NULL, "
                    + "decision_source = CASE WHEN no_show_marked_by IS NULL THEN decision_source_before_no_show ELSE decision_source END, "
                    + "no_show_marked_by = NULL, decision_source_before_no_show = NULL "
                    + "WHERE tenant_id = ? AND id = ? AND status = 'no_show'", tenant, id);
        } catch (DataIntegrityViolationException e) { // the slot was booked by someone else meanwhile (exclusion constraint)
            throw SlotValidator.slotTakenOrRethrow(e);
        }
        askEta(tenant, a);
        var actor = actors.findActor(tenant, staffId).orElseThrow();
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "appointment.no_show_undone", "appointment", id,
                Map.of("status", "no_show"), Map.of("status", "scheduled"));
        log.info("appointment {} no-show undone by {}", id, staffId);
        return appointments.get(tenant, id);
    }

    /** Check-in of a patient whose booking was marked no-show: a walk-in token (ACC-012 places it) linked to the booking. */
    @Transactional
    public QueueEntryResponse lateArrival(UUID staffId, UUID appointmentId) {
        UUID tenant = tenantContext.current();
        AppointmentRow a = repo.findById(tenant, appointmentId).orElseThrow(NoShowService::notFound);
        if (!"no_show".equals(a.status())) {
            throw conflict("not-no-show", "Not a no-show", "Late arrival applies only to an appointment marked as a no-show.", "NOT_NO_SHOW");
        }
        if (a.lateArrivalQueueEntryId() != null) {
            throw conflict("already-checked-in", "Already checked in", "This patient was already checked in through late arrival.", "ALREADY_CHECKED_IN");
        }
        ZoneId zone = clock.zone(tenant);
        if (!a.startTime().atZone(zone).toLocalDate().equals(clock.today(zone))) {
            throw conflict("appointment-not-today", "Appointment is not for today", "Late arrival applies to today's appointments only.", "APPOINTMENT_NOT_TODAY");
        }
        QueueEntryResponse entry = queue.checkIn(tenant, staffId, new CheckInRequest(null, a.patientId(), a.doctorId(), "walk_in", null, null));
        queueRepo.markLateArrival(tenant, entry.id());
        jdbc.update("UPDATE appointments SET late_arrival_queue_entry_id = ? WHERE tenant_id = ? AND id = ? AND status = 'no_show'", entry.id(), tenant, appointmentId);
        var actor = actors.findActor(tenant, staffId).orElseThrow();
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "queue.late_arrival", "queue_entry", entry.id(),
                null, Map.of("appointmentId", appointmentId.toString()));
        return entry;
    }

    /** The job's work for the bound tenant: today's scheduled slot bookings past start + grace, where the policy has auto-mark on. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int autoMarkDue() {
        BookingRules.NoShowRule rule = rules.noShowRule();
        if (!rule.autoMark()) {
            return 0;
        }
        UUID tenant = tenantContext.current();
        ZoneId zone = clock.zone(tenant);
        Instant now = clock.now();
        List<UUID> ids = jdbc.queryForList("""
                UPDATE appointments a SET status = 'no_show', no_show_at = ?, no_show_marked_by = NULL,
                       decision_source_before_no_show = a.decision_source, decision_source = 'system'
                WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.booking_kind = 'slot'
                  AND a.start_time >= ? AND a.start_time <= ?
                  AND NOT EXISTS (SELECT 1 FROM queue_entries q WHERE q.tenant_id = a.tenant_id AND q.appointment_id = a.id)
                RETURNING a.id""",
                UUID.class,
                Timestamp.from(now), tenant, Timestamp.from(ClinicClock.startOf(clock.today(zone), zone)),
                Timestamp.from(now.minus(Duration.ofMinutes(rule.graceMinutes()))));
        for (UUID id : ids) {
            marked(tenant, repo.findById(tenant, id).orElseThrow(), null);
        }
        return ids.size();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    private void marked(UUID tenant, AppointmentRow a, UUID staffId) {
        notifications.cancelBySubject("appointment", a.id(), com.nabd.hms.shared.notify.CancelReason.SUBJECT_CHANGED); // reminders about it are void
        events.publish(tenant, DomainEventType.APPOINTMENT_NO_SHOW, a.id(),
                Map.of("appointmentId", a.id(), "doctorId", a.doctorId(), "markedBy", staffId == null ? "system" : "staff"));
        askEta(tenant, a);
    }

    private void askEta(UUID tenant, AppointmentRow a) {
        eta.afterCommit(tenant, a.doctorId(), a.startTime().atZone(clock.zone(tenant)).toLocalDate());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private static ApiException conflict(String slug, String title, String detail, String code) {
        return new ApiException(HttpStatus.CONFLICT, slug, title, detail, Map.of("code", code));
    }
}
