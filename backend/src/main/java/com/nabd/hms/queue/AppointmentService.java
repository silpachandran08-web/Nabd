package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.AppointmentPage;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final int DEFAULT_SLOT_MINUTES = 15;

    private final AppointmentRepository repo;
    private final ScheduleRepository scheduleRepo;
    private final QueueRepository queueRepo;
    private final TenantContext tenantContext;

    AppointmentService(AppointmentRepository repo, ScheduleRepository scheduleRepo, QueueRepository queueRepo,
                        TenantContext tenantContext) {
        this.repo = repo;
        this.scheduleRepo = scheduleRepo;
        this.queueRepo = queueRepo;
        this.tenantContext = tenantContext;
    }

    /** The 409 on conflict comes from the DB unique index, not a check-then-insert — see V4 migration. */
    @Transactional
    public AppointmentResponse book(UUID tenantId, UUID callerStaffId, AppointmentWriteRequest req) {
        tenantContext.set(tenantId);
        enforceSessionCapacity(req.doctorId(), req.startTime());
        int slotMinutes = resolveSlotMinutes(req.doctorId(), req.startTime());
        Instant end = req.startTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID id = repo.insert(tenantId, req.patientId(), req.doctorId(), req.startTime(), end);
            log.info("appointment {} booked by {}: patient {} with doctor {} at {}",
                    id, callerStaffId, req.patientId(), req.doctorId(), req.startTime());
            return toResponse(repo.findById(tenantId, id).orElseThrow());
        } catch (DataIntegrityViolationException e) {
            log.warn("booking conflict: doctor {} already booked at {}", req.doctorId(), req.startTime());
            throw slotUnavailable();
        }
    }

    @Transactional
    public AppointmentPage list(UUID tenantId, UUID doctorId, UUID patientId, LocalDate date, int limit, String cursor) {
        tenantContext.set(tenantId);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<AppointmentRow> rows = repo.listPage(tenantId, doctorId, patientId, date, limit + 1,
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
        requireScheduled(tenantId, id);
        repo.cancel(tenantId, id, req.reason());
        log.info("appointment {} cancelled by {}: {}", id, callerStaffId, req.reason());
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public AppointmentResponse reschedule(UUID tenantId, UUID callerStaffId, UUID id, RescheduleRequest req) {
        tenantContext.set(tenantId);
        AppointmentRow current = requireScheduled(tenantId, id);

        repo.cancel(tenantId, id, "rescheduled");
        enforceSessionCapacity(current.doctorId(), req.newStartTime()); // cancel above already freed this patient's own slot
        int slotMinutes = resolveSlotMinutes(current.doctorId(), req.newStartTime());
        Instant newEnd = req.newStartTime().plus(slotMinutes, ChronoUnit.MINUTES);
        try {
            UUID newId = repo.insert(tenantId, current.patientId(), current.doctorId(), req.newStartTime(), newEnd);
            log.info("appointment {} rescheduled to {} (new id {}) by {}", id, req.newStartTime(), newId, callerStaffId);
            return toResponse(repo.findById(tenantId, newId).orElseThrow());
        } catch (DataIntegrityViolationException e) {
            // rolls back the cancel too — no reason to leave the old slot released if the new one failed
            log.warn("reschedule conflict: doctor {} already booked at {}", current.doctorId(), req.newStartTime());
            throw slotUnavailable();
        }
    }

    void markTerminal(UUID tenantId, UUID appointmentId, String status) {
        repo.markTerminal(tenantId, appointmentId, status);
    }

    private AppointmentRow requireScheduled(UUID tenantId, UUID id) {
        return repo.findById(tenantId, id)
                .filter(a -> "scheduled".equals(a.status()))
                .orElseThrow(this::notFound);
    }

    private int resolveSlotMinutes(UUID doctorId, Instant start) {
        LocalDate date = start.atZone(ZoneOffset.UTC).toLocalDate();
        int dayOfWeek = date.getDayOfWeek().getValue() % 7;
        LocalTime time = start.atZone(ZoneOffset.UTC).toLocalTime();
        return scheduleRepo.findBlockCovering(doctorId, dayOfWeek, time)
                .map(WorkingHoursRow::slotMinutes)
                .orElse(DEFAULT_SLOT_MINUTES);
    }

    /**
     * NB-098: refuses a booking that would push a capped session over its shared walk-in +
     * scheduled limit. Uses the same per-(doctor,day) advisory lock as QueueService's token
     * assignment — booking and walk-in check-in draw from the same pool, so they need to be
     * serialized against each other, not just against themselves.
     */
    private void enforceSessionCapacity(UUID doctorId, Instant start) {
        LocalDate date = start.atZone(ZoneOffset.UTC).toLocalDate();
        int dayOfWeek = date.getDayOfWeek().getValue() % 7;
        LocalTime time = start.atZone(ZoneOffset.UTC).toLocalTime();

        Optional<WorkingHoursRow> block = scheduleRepo.findBlockCovering(doctorId, dayOfWeek, time);
        if (block.isEmpty() || block.get().maxPatients() == null) {
            return;
        }

        queueRepo.lockDoctorDay(doctorId, date);
        int occupancy = scheduleRepo.countSessionOccupancy(doctorId, date, block.get().startTime(), block.get().endTime());
        if (occupancy >= block.get().maxPatients()) {
            log.warn("session capacity reached: doctor {} on {} ({}/{})", doctorId, date, occupancy, block.get().maxPatients());
            throw sessionFull();
        }
    }

    private AppointmentResponse toResponse(AppointmentRow row) {
        return new AppointmentResponse(row.id(), row.patientId(), row.doctorId(), row.startTime(), row.endTime(), row.status());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException slotUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "slot-unavailable", "Slot no longer available",
                "This doctor already has an appointment at that time.");
    }

    private ApiException sessionFull() {
        return new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                "This session has reached its patient cap (walk-ins and bookings counted together).");
    }
}
