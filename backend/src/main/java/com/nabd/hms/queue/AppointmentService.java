package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.AppointmentPage;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
import com.nabd.hms.queue.dto.CallbackEntryResponse;
import com.nabd.hms.queue.dto.CancelRequest;
import com.nabd.hms.queue.dto.PageMeta;
import com.nabd.hms.queue.dto.RescheduleRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository repo;
    private final ScheduleRepository scheduleRepo;
    private final QueueRepository queueRepo;
    private final WaitlistService waitlistService;
    private final TenantContext tenantContext;

    private final ClinicClock clock;
    private final SlotValidator slotValidator;
    private final EventPublisher events;
    private final com.nabd.hms.shared.notify.NotificationPort notifications;
    private final AppointmentChangeNotifier changeNotifier;

    AppointmentService(AppointmentRepository repo, ScheduleRepository scheduleRepo, QueueRepository queueRepo,
                        WaitlistService waitlistService, TenantContext tenantContext, ClinicClock clock,
                        SlotValidator slotValidator, EventPublisher events,
                        com.nabd.hms.shared.notify.NotificationPort notifications, AppointmentChangeNotifier changeNotifier) {
        this.changeNotifier = changeNotifier;
        this.events = events;
        this.notifications = notifications;
        this.slotValidator = slotValidator;
        this.clock = clock;
        this.repo = repo;
        this.scheduleRepo = scheduleRepo;
        this.queueRepo = queueRepo;
        this.waitlistService = waitlistService;
        this.tenantContext = tenantContext;
    }

    /** The 409 on conflict comes from the DB exclusion constraint, not a check-then-insert — see V312 migration. */
    @Transactional
    public AppointmentResponse book(UUID tenantId, UUID callerStaffId, AppointmentWriteRequest req) {
        tenantContext.set(tenantId);
        requirePatientAndDoctor(tenantId, req.patientId(), req.doctorId());
        ZoneId zone = clock.zone(tenantId);
        LocalDate day = clock.clinicDate(tenantId, req.startTime()); // ACC-005
        Optional<WorkingHoursRow> tokenBlock = scheduleRepo.blockAt(tenantId, req.doctorId(), req.startTime())
                .filter(b -> SessionModes.TOKEN.equals(SessionModes.modeOn(b, day)));
        if (tokenBlock.isPresent()) {
            return bookToken(tenantId, callerStaffId, req, tokenBlock.get(), day, zone);
        }
        int slotMinutes = slotValidator.validate(tenantId, req.doctorId(), req.startTime(), clock.now());
        slotValidator.requireNoLiveOffer(tenantId, req.doctorId(), req.startTime(), null);
        enforceSessionCapacity(tenantId, req.doctorId(), req.startTime());
        Instant end = req.startTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID id = repo.insert(tenantId, req.patientId(), req.doctorId(), req.startTime(), end, req.isFollowUpOrDefault(), callerStaffId == null ? "system" : "staff");
            log.info("appointment {} booked by {}: patient {} with doctor {} at {}",
                    id, callerStaffId, req.patientId(), req.doctorId(), req.startTime());
            published(tenantId, DomainEventType.APPOINTMENT_BOOKED, id, req.doctorId(), req.startTime(), "scheduled", Map.of());
            return toResponse(repo.findById(tenantId, id).orElseThrow());
        } catch (DataIntegrityViolationException | org.springframework.dao.PessimisticLockingFailureException e) {
            log.warn("booking conflict: doctor {} already booked at {}", req.doctorId(), req.startTime());
            throw SlotValidator.slotTakenOrRethrow(e);
        }
    }

    /** ACC-011: a place in a token session. start/end = the session window; the number is reserved under the doctor-day lock. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private AppointmentResponse bookToken(UUID tenantId, UUID callerStaffId, AppointmentWriteRequest req, WorkingHoursRow block,
                                          LocalDate day, ZoneId zone) {
        Instant start = clock.atSecond(tenantId, day, block.startSecond());
        Instant end = clock.atSecond(tenantId, day, block.endSecond());
        slotValidator.validateToken(tenantId, req.doctorId(), block, day, end, clock.now());
        queueRepo.lockDoctorDay(tenantId, req.doctorId(), day);
        enforceCapacity(tenantId, req.doctorId(), day, block, zone);
        int token = scheduleRepo.lowestFreeToken(tenantId, req.doctorId(), block.id(), start, block.maxPatients())
                .orElseThrow(() -> sessionFull(true));
        UUID id = repo.insertToken(tenantId, req.patientId(), req.doctorId(), start, end, req.isFollowUpOrDefault(), block.id(), token);
        log.info("token booking {} by {}: doctor {} token {} on {}", id, callerStaffId, req.doctorId(), token, day); // log-phi-ok: token booking number, not a credential
        published(tenantId, DomainEventType.APPOINTMENT_BOOKED, id, req.doctorId(), start, "scheduled", Map.of());
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public AppointmentPage list(UUID tenantId, UUID doctorId, UUID patientId, LocalDate date, int limit, String cursor) {
        tenantContext.set(tenantId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<AppointmentRow> rows = repo.listPage(tenantId, doctorId, patientId, date == null ? null : clock.dayStart(tenantId, date),
                date == null ? null : clock.dayStart(tenantId, date.plusDays(1)), limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<AppointmentRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new AppointmentPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    @Transactional
    public java.util.List<Long> changeSeq(UUID id) {
        return repo.changeSeq(tenantContext.current(), id);
    }

    @Transactional
    public AppointmentResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public AppointmentResponse cancel(UUID tenantId, UUID callerStaffId, UUID id, CancelRequest req) {
        tenantContext.set(tenantId);
        AppointmentRow current = requireScheduled(tenantId, id);
        repo.cancel(tenantId, id, req.reason());
        notifications.cancelBySubject("appointment", id, com.nabd.hms.shared.notify.CancelReason.SUBJECT_CHANGED); // COM-004: reminders about it are void
        log.info("appointment {} cancelled by {}", id, callerStaffId);
        published(tenantId, DomainEventType.APPOINTMENT_CANCELLED, id, current.doctorId(), current.startTime(), "cancelled",
                Map.of("by", callerStaffId == null ? "system" : "staff", "reasonCode", reasonCode(req.reason())));
        waitlistService.onSlotFreed(tenantId, current.doctorId(), current.startTime());
        if (callerStaffId != null) {
            changeNotifier.cancelled(tenantId, id, current.patientId(), current.startTime()); // ACC-006
        }
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** ACC-006: the old booking after the patient took an offered alternative. No notice: the patient just chose the new time. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    void cancelClinicInitiated(UUID tenantId, AppointmentRow current) {
        repo.cancel(tenantId, current.id(), "clinic_initiated");
        notifications.cancelBySubject("appointment", current.id(), com.nabd.hms.shared.notify.CancelReason.SUBJECT_CHANGED);
        published(tenantId, DomainEventType.APPOINTMENT_CANCELLED, current.id(), current.doctorId(), current.startTime(), "cancelled",
                Map.of("by", "system", "reasonCode", "clinic_initiated"));
        waitlistService.onSlotFreed(tenantId, current.doctorId(), current.startTime());
    }

    /** The free-text desk reason as an event code ([a-z0-9_]{3,40}); shortcut: the code is derived, not picked, until the desk sends coded reasons. */
    static String reasonCode(String reason) {
        String c = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        c = c.length() > 40 ? c.substring(0, 40).replaceAll("_+$", "") : c;
        return c.length() < 3 ? "other" : c;
    }

    @Transactional
    public AppointmentResponse reschedule(UUID tenantId, UUID callerStaffId, UUID id, RescheduleRequest req) {
        tenantContext.set(tenantId);
        AppointmentRow current = requireScheduled(tenantId, id);
        int slotMinutes = slotValidator.validate(tenantId, current.doctorId(), req.newStartTime(), clock.now());
        slotValidator.requireNoLiveOffer(tenantId, current.doctorId(), req.newStartTime(), null);

        repo.cancel(tenantId, id, "rescheduled");
        notifications.cancelBySubject("appointment", id, com.nabd.hms.shared.notify.CancelReason.SUBJECT_CHANGED);
        enforceSessionCapacity(tenantId, current.doctorId(), req.newStartTime()); // cancel above already freed this patient's own slot
        Instant newEnd = req.newStartTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID newId = repo.insert(tenantId, current.patientId(), current.doctorId(), req.newStartTime(), newEnd, current.isFollowUp(), callerStaffId == null ? "system" : "staff");
            log.info("appointment {} rescheduled to {} (new id {}) by {}", id, req.newStartTime(), newId, callerStaffId);
            published(tenantId, DomainEventType.APPOINTMENT_RESCHEDULED, newId, current.doctorId(), req.newStartTime(), "scheduled",
                    Map.of("previousAppointmentId", id, "previousStartTime", current.startTime(),
                            "by", callerStaffId == null ? "system" : "staff", "reasonCode", "rescheduled"));
            waitlistService.onSlotFreed(tenantId, current.doctorId(), current.startTime());
            if (callerStaffId != null) {
                changeNotifier.moved(tenantId, newId, current.patientId(), req.newStartTime()); // ACC-006
            }
            return toResponse(repo.findById(tenantId, newId).orElseThrow());
        } catch (DataIntegrityViolationException | org.springframework.dao.PessimisticLockingFailureException e) {
            // rolls back the cancel too — no reason to leave the old slot released if the new one failed
            log.warn("reschedule conflict: doctor {} already booked at {}", current.doctorId(), req.newStartTime());
            throw SlotValidator.slotTakenOrRethrow(e);
        }
    }

    /** OPS-015: ids, slot time and status only; the patient is never named in an event. */
    private void published(UUID tenantId, DomainEventType type, UUID id, UUID doctorId, Instant startTime, String status,
                           Map<String, Object> extra) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("appointmentId", id);
        data.put("doctorId", doctorId);
        data.put("startTime", startTime);
        data.put("status", status);
        data.putAll(extra);
        events.publish(tenantId, type, id, data);
    }

    void markTerminal(UUID tenantId, UUID appointmentId, String status) {
        repo.markTerminal(tenantId, appointmentId, status);
    }

    /** NB-116: front-desk callback list — missed follow-ups only, not every no-show. */
    @Transactional
    public List<CallbackEntryResponse> callbackList(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listCallbackList(tenantId).stream()
                .map(c -> new CallbackEntryResponse(c.id(), c.patientId(), c.patientName(), c.doctorId(), c.startTime(), c.status()))
                .toList();
    }

    private AppointmentRow requireScheduled(UUID tenantId, UUID id) {
        AppointmentRow a = repo.findById(tenantId, id).orElseThrow(this::notFound);
        if ("arrived".equals(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "appointment-arrived", "Appointment already arrived",
                    "The patient has checked in; the appointment can no longer be cancelled or rescheduled.",
                    java.util.Map.of("code", "APPOINTMENT_ARRIVED"));
        }
        if (!"scheduled".equals(a.status())) {
            throw notFound();
        }
        return a;
    }

    /**
     * NB-098: refuses a booking that would push a capped session over its shared walk-in +
     * scheduled limit. Uses the same per-(doctor,day) advisory lock as QueueService's token
     * assignment — booking and walk-in check-in draw from the same pool, so they need to be
     * serialized against each other, not just against themselves.
     */
    void enforceSessionCapacity(UUID tenantId, UUID doctorId, Instant start) {
        ZoneId zone = clock.zone(tenantId);
        LocalDate date = clock.clinicDate(tenantId, start);
        Optional<WorkingHoursRow> block = scheduleRepo.blockAt(tenantId, doctorId, start);
        if (block.isEmpty() || (block.get().maxPatients() == null && block.get().onlineCap() == null)) {
            return;
        }
        queueRepo.lockDoctorDay(tenantId, doctorId, date);
        enforceCapacity(tenantId, doctorId, date, block.get(), zone);
    }

    /** ACC-011: online cap (bookings only) and the shared total (bookings + walk-ins); the caller holds the doctor-day lock. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private void enforceCapacity(UUID tenantId, UUID doctorId, LocalDate date, WorkingHoursRow block, ZoneId zone) {
        if (block.onlineCap() != null
                && scheduleRepo.countSessionBookings(tenantId, doctorId, date, block) >= block.onlineCap()) {
            log.warn("online cap reached: doctor {} on {} (cap {})", doctorId, date, block.onlineCap());
            throw sessionFull(true);
        }
        if (block.maxPatients() == null) {
            return;
        }
        int occupancy = scheduleRepo.countSessionOccupancy(tenantId, doctorId, date, block);
        if (occupancy >= block.maxPatients()) {
            log.warn("session capacity reached: doctor {} on {} ({}/{})", doctorId, date, occupancy, block.maxPatients());
            throw sessionFull(false);
        }
    }

    /** QA-03: shared with WaitlistService.join(). */
    static void requirePatientAndDoctor(AppointmentRepository repo, UUID tenantId, UUID patientId, UUID doctorId) {
        if (!repo.patientExists(tenantId, patientId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found",
                    "patientId does not reference a patient in this clinic.");
        }
        if (!repo.doctorExists(tenantId, doctorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found",
                    "doctorId does not reference a doctor in this clinic.");
        }
    }

    private void requirePatientAndDoctor(UUID tenantId, UUID patientId, UUID doctorId) {
        requirePatientAndDoctor(repo, tenantId, patientId, doctorId);
    }

    private AppointmentResponse toResponse(AppointmentRow row) {
        return new AppointmentResponse(row.id(), row.patientId(), row.doctorId(), row.startTime(), row.endTime(),
                row.status(), row.isFollowUp(), row.cancelReason(), row.decisionSource(), row.channel(), row.bookingKind(), row.sessionToken(), expectedRange(row));
    }

    /** ACC-011: a token booking shows an expected range from the average consult time, never a fixed time. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private String expectedRange(AppointmentRow row) {
        if (!"token".equals(row.bookingKind()) || row.sessionToken() == null) {
            return null;
        }
        double avg = queueRepo.averageRecentVisitMinutes(row.tenantId(), row.doctorId(), 30).orElse(15.0);
        return SessionModes.expectedRange(row.startTime(), row.endTime(), row.sessionToken(), avg, clock.zone(row.tenantId()));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException sessionFull(boolean online) {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                online ? "Online booking for this session is full; the desk may still add walk-ins."
                        : "This session has reached its patient cap (walk-ins and bookings counted together).",
                java.util.Map.of("code", "SESSION_FULL", "scope", online ? "online" : "total"));
    }
}
