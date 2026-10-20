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

    AppointmentService(AppointmentRepository repo, ScheduleRepository scheduleRepo, QueueRepository queueRepo,
                        WaitlistService waitlistService, TenantContext tenantContext, ClinicClock clock,
                        SlotValidator slotValidator, EventPublisher events) {
        this.events = events;
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
        int slotMinutes = slotValidator.validate(tenantId, req.doctorId(), req.startTime(), clock.now());
        enforceSessionCapacity(tenantId, req.doctorId(), req.startTime());
        Instant end = req.startTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID id = repo.insert(tenantId, req.patientId(), req.doctorId(), req.startTime(), end, req.isFollowUpOrDefault());
            log.info("appointment {} booked by {}: patient {} with doctor {} at {}",
                    id, callerStaffId, req.patientId(), req.doctorId(), req.startTime());
            published(tenantId, DomainEventType.APPOINTMENT_BOOKED, id, req.doctorId(), req.startTime(), "scheduled", Map.of());
            return toResponse(repo.findById(tenantId, id).orElseThrow());
        } catch (DataIntegrityViolationException e) {
            log.warn("booking conflict: doctor {} already booked at {}", req.doctorId(), req.startTime());
            throw SlotValidator.slotTakenOrRethrow(e);
        }
    }

    @Transactional
    public AppointmentPage list(UUID tenantId, UUID doctorId, UUID patientId, LocalDate date, int limit, String cursor) {
        tenantContext.set(tenantId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<AppointmentRow> rows = repo.listPage(tenantId, doctorId, patientId, date, clock.zone(tenantId), limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<AppointmentRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new AppointmentPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
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
        log.info("appointment {} cancelled by {}", id, callerStaffId);
        published(tenantId, DomainEventType.APPOINTMENT_CANCELLED, id, current.doctorId(), current.startTime(), "cancelled", Map.of());
        waitlistService.onSlotFreed(tenantId, current.doctorId(), current.startTime());
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public AppointmentResponse reschedule(UUID tenantId, UUID callerStaffId, UUID id, RescheduleRequest req) {
        tenantContext.set(tenantId);
        AppointmentRow current = requireScheduled(tenantId, id);
        int slotMinutes = slotValidator.validate(tenantId, current.doctorId(), req.newStartTime(), clock.now());

        repo.cancel(tenantId, id, "rescheduled");
        enforceSessionCapacity(tenantId, current.doctorId(), req.newStartTime()); // cancel above already freed this patient's own slot
        Instant newEnd = req.newStartTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID newId = repo.insert(tenantId, current.patientId(), current.doctorId(), req.newStartTime(), newEnd, current.isFollowUp());
            log.info("appointment {} rescheduled to {} (new id {}) by {}", id, req.newStartTime(), newId, callerStaffId);
            published(tenantId, DomainEventType.APPOINTMENT_RESCHEDULED, newId, current.doctorId(), req.newStartTime(), "scheduled",
                    Map.of("previousAppointmentId", id, "previousStartTime", current.startTime()));
            waitlistService.onSlotFreed(tenantId, current.doctorId(), current.startTime());
            return toResponse(repo.findById(tenantId, newId).orElseThrow());
        } catch (DataIntegrityViolationException e) {
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
    private void enforceSessionCapacity(UUID tenantId, UUID doctorId, Instant start) {
        ZoneId zone = clock.zone(tenantId);
        LocalDate date = start.atZone(zone).toLocalDate();
        int dayOfWeek = date.getDayOfWeek().getValue() % 7;
        LocalTime time = start.atZone(zone).toLocalTime();

        Optional<WorkingHoursRow> block = scheduleRepo.findBlockCovering(doctorId, dayOfWeek, time);
        if (block.isEmpty() || block.get().maxPatients() == null) {
            return;
        }

        queueRepo.lockDoctorDay(tenantId, doctorId, date);
        int occupancy = scheduleRepo.countSessionOccupancy(tenantId, doctorId, date, block.get().startTime(), block.get().endTime(), zone);
        if (occupancy >= block.get().maxPatients()) {
            log.warn("session capacity reached: doctor {} on {} ({}/{})", doctorId, date, occupancy, block.get().maxPatients());
            throw sessionFull();
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
                row.status(), row.isFollowUp(), row.cancelReason());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException sessionFull() {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                "This session has reached its patient cap (walk-ins and bookings counted together).", java.util.Map.of("code", "SESSION_FULL"));
    }
}
