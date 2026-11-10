package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;

/**
 * ACC-006: alternatives for a confirmed booking staff moved or blocked. The desk lists the affected bookings with the nearest
 * valid slots, sends an offer (ids and times only, no free text), and the patient's tap books the new slot and cancels the old
 * one with reason clinic_initiated. A slot taken meanwhile answers 409 SLOT_TAKEN with the next alternative.
 * shortcut: same-doctor alternatives only; same-specialty doctors need an owner setting that does not exist yet.
 */
@Service
class ChangeOfferService {

    static final Duration OFFER_TTL = Duration.ofHours(2);
    private static final int ALTERNATIVES = 3;

    @Schema(name = "AffectedAppointment")
    record AffectedItem(UUID appointmentId, UUID patientId, String patientRef, Instant start, boolean channelConsent, List<Instant> alternatives, String offerStatus) {
    }

    @Schema(name = "AffectedAppointmentList")
    record AffectedList(List<AffectedItem> items) {
    }

    private final TenantScope tenantScope;
    private final TenantContext tenantContext;
    private final ChangeOfferRepository repo;
    private final ScheduleService schedule;
    private final ScheduleRepository scheduleRepo;
    private final AppointmentService appointments;
    private final AppointmentRepository appointmentRepo;
    private final SlotAlternatives alternatives;
    private final SlotValidator slotValidator;
    private final PatientRefs patientRefs;
    private final ConsentRegistry consents;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final TransactionTemplate nested;

    ChangeOfferService(TenantScope tenantScope, TenantContext tenantContext, ChangeOfferRepository repo, ScheduleService schedule,
                       ScheduleRepository scheduleRepo, AppointmentService appointments, AppointmentRepository appointmentRepo,
                       SlotAlternatives alternatives, SlotValidator slotValidator, PatientRefs patientRefs, ConsentRegistry consents,
                       ActorDirectory actors, AuditService audit, EventPublisher events, ClinicClock clock, PlatformTransactionManager txm) {
        this.tenantScope = tenantScope;
        this.tenantContext = tenantContext;
        this.repo = repo;
        this.schedule = schedule;
        this.scheduleRepo = scheduleRepo;
        this.appointments = appointments;
        this.appointmentRepo = appointmentRepo;
        this.alternatives = alternatives;
        this.slotValidator = slotValidator;
        this.patientRefs = patientRefs;
        this.consents = consents;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Transactional
    AffectedList affected(UUID doctorId, UUID leaveId) {
        UUID tid = tenantContext.current();
        List<AffectedItem> items = new ArrayList<>();
        for (var b : schedule.leaveBookings(tid, doctorId, leaveId)) {
            AppointmentRow a = appointmentRepo.findById(tid, b.id()).orElse(null);
            if (a == null || !"scheduled".equals(a.status())) continue;
            items.add(new AffectedItem(a.id(), a.patientId(), patientRefs.refFor(a.patientId()), a.startTime(),
                    consents.isGranted(a.patientId(), ConsentRegistry.Purpose.MESSAGING), nearest(tid, a.doctorId(), a.startTime()), offerStatus(tid, a.id())));
        }
        return new AffectedList(items);
    }

    @Transactional
    Map<String, Object> offer(UUID staffId, UUID appointmentId, List<Instant> requested) {
        UUID tid = tenantContext.current();
        AppointmentRow a = appointmentRepo.findById(tid, appointmentId).orElseThrow(ApiException::notFound);
        if (!"scheduled".equals(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "appointment-not-scheduled", "Appointment not scheduled",
                    "Only a confirmed booking can be offered other times.", Map.of("code", "APPOINTMENT_NOT_SCHEDULED"));
        }
        if (!consents.isGranted(a.patientId(), ConsentRegistry.Purpose.MESSAGING)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "no-messaging-consent", "No messaging consent",
                    "The patient has not agreed to messages; call the patient instead.", Map.of("code", "NO_MESSAGING_CONSENT"));
        }
        List<Instant> slots = requested == null || requested.isEmpty() ? nearest(tid, a.doctorId(), a.startTime()) : checked(tid, a, requested);
        if (slots.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "no-alternatives", "No alternative times", "This doctor has no free time in the next two weeks.",
                    Map.of("code", "NO_ALTERNATIVES"));
        }
        Instant expires = clock.now().plus(OFFER_TTL);
        repo.supersedeOpen(tid, appointmentId);
        UUID offerId = repo.insert(tid, appointmentId, repo.reasonFor(tid, appointmentId), slots, expires, staffId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointmentId);
        data.put("doctorId", a.doctorId());
        data.put("startTime", a.startTime());
        data.put("outcome", "suggested");
        data.put("offerKind", "change");
        data.put("offerId", offerId);
        data.put("slots", slots);
        data.put("expiresAt", expires);
        events.publish(tid, DomainEventType.BOOKING_ROUTED, appointmentId, data);
        var actor = actors.findActor(tid, staffId).orElseThrow();
        audit.record(tid, "staff", staffId, actor.name(), actor.role(), null, "appointment.change_offer_sent", "appointment", appointmentId, null,
                Map.of("offerId", offerId, "slotCount", slots.size()));
        return Map.of("offerId", offerId, "slots", slots, "expiresAt", expires);
    }

    Map<String, Object> accept(UUID tid, ServiceIdentity edge, UUID offerId, Instant slotStart) {
        return tenantScope.run(tid, () -> {
            ChangeOfferRepository.Offer o = repo.lock(tid, offerId).orElseThrow(ApiException::notFound);
            if ("accepted".equals(o.status())) {
                return Map.of("newAppointmentId", o.newAppointmentId(), "status", "CONFIRMED"); // a repeated tap is the same answer
            }
            AppointmentRow old = appointmentRepo.findById(tid, o.appointmentId()).orElseThrow(ApiException::notFound);
            if (!"sent".equals(o.status()) || !o.expiresAt().isAfter(clock.now()) || !"scheduled".equals(old.status())) throw gone();
            if (!o.slots().contains(slotStart)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid slot", "slotStart must be one of the offered times.");
            }
            UUID newId;
            try {
                newId = nested.execute(s -> appointments.book(tid, null, new AppointmentWriteRequest(old.patientId(), old.doctorId(), slotStart, old.isFollowUp())).id());
            } catch (ApiException e) { // taken, now on leave, or the session filled up: the patient needs another time
                Map<String, Object> extras = new LinkedHashMap<>();
                extras.put("code", "SLOT_TAKEN");
                alternatives.nearest(tid, old.doctorId(), slotStart).ifPresent(n -> extras.put("nextAlternative", n.toString()));
                throw new ApiException(HttpStatus.CONFLICT, "slot-taken", "Slot no longer available", "That time is no longer free.", extras);
            }
            appointments.cancelClinicInitiated(tid, old);
            repo.close(tid, offerId, "accepted", slotStart, newId);
            audit.record(tid, "system", null, InternalSchedulingService.ACTOR, edge.serviceId(), null, "appointment.change_offer_accepted", "appointment",
                    o.appointmentId(), null, Map.of("offerId", offerId, "newAppointmentId", newId));
            return Map.of("newAppointmentId", newId, "status", "CONFIRMED");
        });
    }

    /** At most three free slots after the original time, nearest first. Needs the caller's transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<Instant> nearest(UUID tid, UUID doctorId, Instant after) {
        List<Instant> out = new ArrayList<>();
        Instant from = after;
        while (out.size() < ALTERNATIVES) {
            var next = alternatives.nearest(tid, doctorId, from);
            if (next.isEmpty()) break;
            out.add(next.get());
            from = next.get();
        }
        return out;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<Instant> checked(UUID tid, AppointmentRow a, List<Instant> slots) {
        if (slots.size() > ALTERNATIVES || slots.stream().anyMatch(java.util.Objects::isNull) || slots.stream().distinct().count() != slots.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid slots", "slots: one to three different times.");
        }
        Instant now = clock.now();
        for (Instant s : slots) {
            slotValidator.validate(tid, a.doctorId(), s, now);
            if (scheduleRepo.bookedStartTimes(a.doctorId(), s, s.plusSeconds(1)).contains(s)) {
                throw new ApiException(HttpStatus.CONFLICT, "slot-taken", "Slot no longer available", "That time is no longer free.", Map.of("code", "SLOT_TAKEN"));
            }
        }
        return slots;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private String offerStatus(UUID tid, UUID appointmentId) {
        return repo.latestFor(tid, appointmentId).map(o -> "sent".equals(o.status()) && !o.expiresAt().isAfter(clock.now()) ? "expired" : o.status()).orElse(null);
    }

    private static ApiException gone() {
        return new ApiException(HttpStatus.GONE, "offer-expired", "Offer expired", "This offer is no longer open.", Map.of("code", "OFFER_EXPIRED"));
    }
}
