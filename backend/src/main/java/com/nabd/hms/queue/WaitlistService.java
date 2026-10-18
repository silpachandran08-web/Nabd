package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.ActorInfo;
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

    /** REC-028: entries the front desk may still book into a chosen slot (booked/cancelled are final). */
    private static final Set<String> CHOSEN_SLOT_STATUSES = Set.of("waiting", "offered", "expired");

    private final WaitlistRepository repo;
    private final AppointmentRepository appointmentRepo;
    private final ScheduleRepository scheduleRepo;
    private final QueueRepository queueRepo;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    private final ClinicClock clock;

    WaitlistService(WaitlistRepository repo, AppointmentRepository appointmentRepo, ScheduleRepository scheduleRepo,
                     QueueRepository queueRepo, TenantContext tenantContext, AuditService auditService, ClinicClock clock) {
        this.clock = clock;
        this.repo = repo;
        this.appointmentRepo = appointmentRepo;
        this.scheduleRepo = scheduleRepo;
        this.queueRepo = queueRepo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
    }

    @Transactional
    public WaitlistEntryResponse join(UUID tenantId, UUID doctorId, UUID patientId) {
        tenantContext.set(tenantId);
        AppointmentService.requirePatientAndDoctor(appointmentRepo, tenantId, patientId, doctorId);
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
        return accept(tenantId, callerStaffId, id, null);
    }

    /**
     * chosenSlotStart null = accept the offered slot (unchanged NB-099 behaviour). Non-null (REC-028) = book
     * this entry into that slot instead; if it equals the entry's live offer it is the plain accept.
     */
    @Transactional
    public WaitlistEntryResponse accept(UUID tenantId, UUID callerStaffId, UUID id, Instant chosenSlotStart) {
        tenantContext.set(tenantId);
        WaitlistEntryRow entry = repo.findById(tenantId, id).orElseThrow(this::notFound);
        expireAndCascade(tenantId, entry.doctorId());
        entry = repo.findById(tenantId, id).orElseThrow(this::notFound); // re-read: may have just expired/cascaded above

        if (chosenSlotStart != null
                && !("offered".equals(entry.status()) && chosenSlotStart.equals(entry.offeredSlotStart()))) {
            return bookChosenSlot(tenantId, callerStaffId, entry, chosenSlotStart);
        }

        if (!"offered".equals(entry.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "offer-expired", "Offer no longer available",
                    "This waitlist offer has expired or was already used.");
        }

        int slotMinutes = resolveSlotMinutes(tenantId, entry.doctorId(), entry.offeredSlotStart());
        Instant end = entry.offeredSlotStart().plus(slotMinutes, ChronoUnit.MINUTES);
        UUID appointmentId;
        try {
            appointmentId = appointmentRepo.insert(tenantId, entry.patientId(), entry.doctorId(), entry.offeredSlotStart(), end, false);
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

    /**
     * REC-028: the front desk books a waitlisted patient into a slot it chose. Same rules as
     * AppointmentService.book() (clinic holiday 400, session cap 409 session-full, the DB unique index for a taken
     * slot 409 slot-unavailable), duplicated here because this class cannot depend on AppointmentService (it
     * calls onSlotFreed()). On top of those: the slot must be in the future, and a slot currently offered to
     * someone else on the waitlist is refused (409 slot-on-offer) so the single-accept rule holds. If this
     * entry itself held a live offer of a different slot, that slot passes to the next waiting patient.
     */
    private WaitlistEntryResponse bookChosenSlot(UUID tenantId, UUID callerStaffId, WaitlistEntryRow entry, Instant slotStart) {
        if (!CHOSEN_SLOT_STATUSES.contains(entry.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "waitlist-entry-closed", "Waitlist entry closed",
                    "This waitlist entry was already booked or removed.");
        }
        if (!slotStart.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "slot-in-past", "Slot in the past",
                    "slotStart must be in the future.");
        }
        UUID doctorId = entry.doctorId();
        ZoneId zone = clock.zone(tenantId);
        LocalDate date = slotStart.atZone(zone).toLocalDate();
        if (scheduleRepo.isClinicHoliday(tenantId, date)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clinic-holiday", "Clinic closed",
                    "The clinic is closed on " + date + ".");
        }
        if (repo.isSlotOnLiveOffer(tenantId, doctorId, slotStart, entry.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "slot-on-offer", "Slot is on offer",
                    "This slot is currently offered to another patient on the waitlist.");
        }
        enforceSessionCapacity(tenantId, doctorId, slotStart, zone);

        int slotMinutes = resolveSlotMinutes(tenantId, doctorId, slotStart);
        Instant end = slotStart.plus(slotMinutes, ChronoUnit.MINUTES);
        UUID appointmentId;
        try {
            appointmentId = appointmentRepo.insert(tenantId, entry.patientId(), doctorId, slotStart, end, false);
        } catch (DataIntegrityViolationException e) {
            log.warn("waitlist chosen-slot conflict: doctor {} already booked at {}", doctorId, slotStart);
            throw new ApiException(HttpStatus.CONFLICT, "slot-unavailable", "Slot no longer available",
                    "This doctor already has an appointment at that time.");
        }
        if (!repo.claimForChosenSlot(tenantId, entry.id(), appointmentId)) {
            appointmentRepo.cancel(tenantId, appointmentId, "waitlist-entry-closed");
            throw new ApiException(HttpStatus.CONFLICT, "waitlist-entry-closed", "Waitlist entry closed",
                    "This waitlist entry was already booked or removed.");
        }
        if ("offered".equals(entry.status()) && entry.offeredSlotStart() != null) {
            // the slot this entry was holding is free again: offer it on, exactly as an expiry would
            Instant releasedSlot = entry.offeredSlotStart();
            repo.findOldestWaiting(tenantId, doctorId).ifPresent(next -> {
                Instant expiresAt = Instant.now().plus(OFFER_WINDOW_MINUTES, ChronoUnit.MINUTES);
                repo.offer(tenantId, next.id(), releasedSlot, expiresAt);
                log.info("waitlist entry {} (patient {}) offered released slot {} for doctor {}, expires {}",
                        next.id(), next.patientId(), releasedSlot, doctorId, expiresAt);
            });
        }
        // ids and the slot only: no patient name or phone in the audit row
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "booked");
        after.put("appointmentId", appointmentId.toString());
        after.put("doctorId", doctorId.toString());
        after.put("patientId", entry.patientId().toString());
        after.put("slotStart", slotStart.toString());
        audit(tenantId, callerStaffId, "waitlist.booked_chosen_slot", entry.id(), Map.of("status", entry.status()), after);
        log.info("waitlist entry {} booked by {} into chosen slot {}: appointment {} with doctor {}",
                entry.id(), callerStaffId, slotStart, appointmentId, doctorId);
        return toResponse(repo.findById(tenantId, entry.id()).orElseThrow());
    }

    /** Same NB-098 rule and advisory lock as AppointmentService.enforceSessionCapacity(). */
    private void enforceSessionCapacity(UUID tenantId, UUID doctorId, Instant start, ZoneId zone) {
        LocalDate date = start.atZone(zone).toLocalDate();
        int dayOfWeek = date.getDayOfWeek().getValue() % 7;
        LocalTime time = start.atZone(zone).toLocalTime();
        Optional<WorkingHoursRow> block = scheduleRepo.findBlockCovering(doctorId, dayOfWeek, time);
        if (block.isEmpty() || block.get().maxPatients() == null) {
            return;
        }
        queueRepo.lockDoctorDay(doctorId, date);
        int occupancy = scheduleRepo.countSessionOccupancy(tenantId, doctorId, date, block.get().startTime(), block.get().endTime(), zone);
        if (occupancy >= block.get().maxPatients()) {
            throw new ApiException(HttpStatus.CONFLICT, "session-full", "Session is full",
                    "This session has reached its patient cap (walk-ins and bookings counted together).");
        }
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "waitlist_entry", entityId, before, after);
    }

    @Transactional
    public void cancelMembership(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, id).orElseThrow(this::notFound);
        repo.cancelMembership(tenantId, id);
    }

    private int resolveSlotMinutes(UUID tenantId, UUID doctorId, Instant start) {
        java.time.ZonedDateTime local = start.atZone(clock.zone(tenantId));
        int dayOfWeek = local.getDayOfWeek().getValue() % 7;
        LocalTime time = local.toLocalTime();
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
