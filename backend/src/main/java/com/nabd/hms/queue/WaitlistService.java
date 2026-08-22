package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.WaitlistEntryResponse;
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
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WaitlistEntryRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/**
 * NB-099: a freed appointment slot is offered to the doctor's waitlist, oldest join first, with a
 * 15-minute window to accept. Booking a waitlist offer bypasses AppointmentService.book()'s
 * holiday/capacity checks deliberately — this exact slot already passed them once when it was
 * first booked, cancelling doesn't reopen the clinic on a holiday or change a session's cap, and
 * going straight to AppointmentRepository also sidesteps a circular dependency (AppointmentService
 * calls onSlotFreed() below, so this class can't depend back on AppointmentService).
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);
    private static final int OFFER_WINDOW_MINUTES = 15;
    private static final int DEFAULT_SLOT_MINUTES = 15;

    private final WaitlistRepository repo;
    private final AppointmentRepository appointmentRepo;
    private final ScheduleRepository scheduleRepo;
    private final TenantContext tenantContext;

    WaitlistService(WaitlistRepository repo, AppointmentRepository appointmentRepo, ScheduleRepository scheduleRepo,
                     TenantContext tenantContext) {
        this.repo = repo;
        this.appointmentRepo = appointmentRepo;
        this.scheduleRepo = scheduleRepo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public WaitlistEntryResponse join(UUID tenantId, UUID doctorId, UUID patientId) {
        tenantContext.set(tenantId);
        try {
            UUID id = repo.insert(tenantId, doctorId, patientId);
            log.info("patient {} joined waitlist {} for doctor {}", patientId, id, doctorId);
            return toResponse(repo.findById(tenantId, id).orElseThrow());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "already-waitlisted", "Already on the waitlist",
                    "This patient is already waiting for this doctor.");
        }
    }

    @Transactional
    public List<WaitlistEntryResponse> list(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        expireAndCascade(tenantId, doctorId);
        return repo.listForDoctor(tenantId, doctorId).stream().map(this::toResponse).toList();
    }

    /** Called from AppointmentService.cancel() right after a slot is freed. */
    @Transactional
    void onSlotFreed(UUID tenantId, UUID doctorId, Instant freedSlotStart) {
        expireAndCascade(tenantId, doctorId);
        repo.findOldestWaiting(tenantId, doctorId).ifPresent(entry -> {
            Instant expiresAt = Instant.now().plus(OFFER_WINDOW_MINUTES, ChronoUnit.MINUTES);
            repo.offer(tenantId, entry.id(), freedSlotStart, expiresAt);
            log.info("waitlist entry {} (patient {}) offered freed slot {} for doctor {}, expires {}",
                    entry.id(), entry.patientId(), freedSlotStart, doctorId, expiresAt);
        });
    }

    /** Closes out any offer past its 15-minute window and re-offers the same slot to whoever is next in line. */
    private void expireAndCascade(UUID tenantId, UUID doctorId) {
        for (WaitlistEntryRow stale : repo.findStaleOffers(tenantId, doctorId)) {
            repo.markExpired(tenantId, stale.id());
            log.info("waitlist offer {} expired unaccepted (patient {}, slot {})", stale.id(), stale.patientId(), stale.offeredSlotStart());
            repo.findOldestWaiting(tenantId, doctorId).ifPresent(next -> {
                Instant expiresAt = Instant.now().plus(OFFER_WINDOW_MINUTES, ChronoUnit.MINUTES);
                repo.offer(tenantId, next.id(), stale.offeredSlotStart(), expiresAt);
                log.info("waitlist entry {} (patient {}) re-offered slot {} for doctor {}, expires {}",
                        next.id(), next.patientId(), stale.offeredSlotStart(), doctorId, expiresAt);
            });
        }
    }

    @Transactional
    public WaitlistEntryResponse accept(UUID tenantId, UUID callerStaffId, UUID id) {
        tenantContext.set(tenantId);
        WaitlistEntryRow entry = repo.findById(tenantId, id).orElseThrow(this::notFound);
        expireAndCascade(tenantId, entry.doctorId());
        entry = repo.findById(tenantId, id).orElseThrow(this::notFound); // re-read: may have just expired/cascaded above

        if (!"offered".equals(entry.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "offer-expired", "Offer no longer available",
                    "This waitlist offer has expired or was already used.");
        }

        int slotMinutes = resolveSlotMinutes(entry.doctorId(), entry.offeredSlotStart());
        Instant end = entry.offeredSlotStart().plus(slotMinutes, ChronoUnit.MINUTES);
        UUID appointmentId;
        try {
            appointmentId = appointmentRepo.insert(tenantId, entry.patientId(), entry.doctorId(), entry.offeredSlotStart(), end);
        } catch (DataIntegrityViolationException e) {
            // vanishingly unlikely (the slot was free the moment it was offered) but never silently drop the claim
            throw new ApiException(HttpStatus.CONFLICT, "slot-unavailable", "Slot no longer available",
                    "This slot was booked through another path just now.");
        }
        if (!repo.claimOffer(tenantId, id, appointmentId)) {
            // lost the race against expiry between the check above and this claim — undo the booking
            appointmentRepo.cancel(tenantId, appointmentId, "waitlist-offer-expired");
            throw new ApiException(HttpStatus.CONFLICT, "offer-expired", "Offer no longer available",
                    "This waitlist offer has expired or was already used.");
        }
        log.info("waitlist entry {} accepted by {}: appointment {} booked for patient {} with doctor {}",
                id, callerStaffId, appointmentId, entry.patientId(), entry.doctorId());
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public void cancelMembership(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, id).orElseThrow(this::notFound);
        repo.cancelMembership(tenantId, id);
    }

    private int resolveSlotMinutes(UUID doctorId, Instant start) {
        LocalDate date = start.atZone(ZoneOffset.UTC).toLocalDate();
        int dayOfWeek = date.getDayOfWeek().getValue() % 7;
        LocalTime time = start.atZone(ZoneOffset.UTC).toLocalTime();
        return scheduleRepo.findBlockCovering(doctorId, dayOfWeek, time)
                .map(WorkingHoursRow::slotMinutes)
                .orElse(DEFAULT_SLOT_MINUTES);
    }

    private WaitlistEntryResponse toResponse(WaitlistEntryRow row) {
        return new WaitlistEntryResponse(row.id(), row.doctorId(), row.patientId(), row.patientName(), row.joinedAt(),
                row.status(), row.offeredSlotStart(), row.offerExpiresAt(), row.bookedAppointmentId());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
