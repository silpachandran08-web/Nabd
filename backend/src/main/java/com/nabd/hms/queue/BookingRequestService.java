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
import com.nabd.hms.patient.api.PatientRefs;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.BookingRequestRepository.Req;

/**
 * ACC-009 + ACC-010. One path for every channel: a booking from a patient channel is confirmed or held for review by
 * {@link BookingDecision}; a held request keeps its slot (the exclusion constraint covers status 'requested') until reception
 * accepts, moves, offers other slots or declines it, each through a compare-and-set on status = 'requested'. Events go through
 * the outbox (so a confirmation is only sent after commit). The patient's note is stored for the reviewer and never logged,
 * audited or put in an event; the audit rows carry ids and codes only.
 */
@Service
class BookingRequestService {

    static final Set<String> CHANNELS = Set.of("desk", "whatsapp", "web", "widget", "app", "call_centre", "aggregator");
    static final Set<String> VISIT_TYPES = Set.of("new", "follow_up", "procedure", "tele");
    private static final Duration OFFER_TTL = Duration.ofHours(1);

    @Schema(name = "BookingRequestItem")
    public record Item(UUID appointmentId, Patient patient, UUID doctorId, Instant start, String channel, String reviewReason,
                       List<String> triageFlags, Instant slaDueAt, Instant suggestedAlternative, String status) {
    }

    @Schema(name = "BookingRequestPatient")
    public record Patient(String displayName, boolean isNew, Integer age) {
    }

    @Schema(name = "BookingRequestStats")
    public record Stats(int autoAccepted, Double medianReplySeconds) {
    }

    @Schema(name = "BookingRequestPage")
    public record Page(List<Item> items, String nextCursor, Stats stats) {
    }

    private final TenantScope tenantScope;
    private final TenantContext tenantContext;
    private final BookingRequestRepository repo;
    private final AppointmentService appointments;
    private final AppointmentRepository appointmentRepo;
    private final QueueRepository queueRepo;
    private final ScheduleRepository scheduleRepo;
    private final SlotValidator slotValidator;
    private final SlotAlternatives alternatives;
    private final WaitlistService waitlist;
    private final PatientRefs patientRefs;
    private final ActorDirectory actors;
    private final AuditService audit;
    private final EventPublisher events;
    private final ClinicClock clock;
    private final com.nabd.hms.shared.notify.NotificationPort notifications;

    BookingRequestService(TenantScope tenantScope, TenantContext tenantContext, BookingRequestRepository repo, AppointmentService appointments,
                          AppointmentRepository appointmentRepo, QueueRepository queueRepo, ScheduleRepository scheduleRepo, SlotValidator slotValidator,
                          SlotAlternatives alternatives, WaitlistService waitlist, PatientRefs patientRefs, ActorDirectory actors, AuditService audit,
                          EventPublisher events, ClinicClock clock, com.nabd.hms.shared.notify.NotificationPort notifications) {
        this.tenantScope = tenantScope;
        this.tenantContext = tenantContext;
        this.repo = repo;
        this.appointments = appointments;
        this.appointmentRepo = appointmentRepo;
        this.queueRepo = queueRepo;
        this.scheduleRepo = scheduleRepo;
        this.slotValidator = slotValidator;
        this.alternatives = alternatives;
        this.waitlist = waitlist;
        this.patientRefs = patientRefs;
        this.actors = actors;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.notifications = notifications;
    }

    // ---- ACC-009: a booking from a patient channel ------------------------------------------------------------------------

    Map<String, Object> route(UUID tid, ServiceIdentity edge, String patientRef, UUID doctorId, Instant slotStart, String visitType, String channel,
                              List<String> triageFlags, boolean group, String note) {
        if (!CHANNELS.contains(channel)) throw invalid("channel must be one of " + String.join(", ", CHANNELS) + ".");
        if (visitType != null && !VISIT_TYPES.contains(visitType)) throw invalid("visitType must be new, follow_up, procedure or tele.");
        List<String> flags = triageFlags == null ? List.of() : triageFlags;
        if (flags.size() > 10 || flags.stream().anyMatch(f -> f == null || !f.matches("[a-z0-9_]{1,40}"))) {
            throw invalid("triageFlags: at most 10 codes of lower-case letters, digits and _.");
        }
        if (note != null && note.length() > 280) throw invalid("note: at most 280 characters.");
        return tenantScope.run(tid, () -> {
            UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(BookingRequestService::notOwner);
            AppointmentService.requirePatientAndDoctor(appointmentRepo, tid, patientId, doctorId);
            Instant now = clock.now();
            int minutes = slotValidator.validate(tid, doctorId, slotStart, now);
            ZoneId zone = clock.zone(tid);
            LocalDate day = clock.clinicDate(tid, slotStart);
            queueRepo.lockDoctorDay(tid, doctorId, day); // serialises the cap count against the insert below
            appointments.enforceSessionCapacity(tid, doctorId, slotStart);
            BookingDecision.Policy policy = repo.policy(tid);
            String type = visitType != null && !visitType.equals("new") && !visitType.equals("follow_up") ? visitType
                    : repo.hasHistory(tid, patientId, doctorId) ? "follow_up" : "new";
            int autoToday = repo.autoConfirmed(tid, doctorId, clock.dayStart(tid, day), clock.dayStart(tid, day.plusDays(1)));
            BookingDecision.Outcome o = BookingDecision.decide(policy, new BookingDecision.Facts(type, !flags.isEmpty(), group, autoToday));
            Instant end = slotStart.plus(minutes, ChronoUnit.MINUTES);
            UUID id;
            try {
                id = repo.insertRouted(tid, patientId, doctorId, slotStart, end, o.confirmed() ? "scheduled" : "requested", channel, type,
                        o.confirmed() ? "auto" : null, policy.version(), o.confirmed() ? now : null);
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                throw SlotValidator.slotTakenOrRethrow(e);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("appointmentId", id);
            if (o.confirmed()) {
                out.put("status", "CONFIRMED");
                publishBooked(tid, id, doctorId, slotStart, "scheduled");
                routed(tid, id, doctorId, slotStart, "confirmed", Map.of("channel", channel, "decisionSource", "auto"));
                record(tid, edge, "booking.auto_confirmed", id, Map.of("patientRef", patientRef, "channel", channel, "policyVersion", policy.version()));
            } else {
                Instant sla = now.plus(policy.reviewSlaMinutes(), ChronoUnit.MINUTES);
                repo.insertDetails(tid, id, o.reviewReason(), flags, note, sla);
                out.put("status", "REQUESTED");
                out.put("slaDueAt", sla);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("channel", channel);
                extra.put("reviewReason", o.reviewReason());
                extra.put("slaDueAt", sla);
                routed(tid, id, doctorId, slotStart, "requested", extra);
                record(tid, edge, "booking.requested", id, Map.of("patientRef", patientRef, "channel", channel, "reviewReason", o.reviewReason(), "policyVersion", policy.version()));
            }
            return out;
        });
    }

    // ---- ACC-010: the reviewer's inbox ------------------------------------------------------------------------------------

    @Transactional
    public Page list(boolean open, UUID doctorId, int limit, String cursor) {
        UUID tid = tenantContext.current();
        int offset = offsetOf(cursor);
        Instant dayStart = clock.dayStart(tid, clock.clinicDate(tid));
        List<BookingRequestRepository.ListRow> rows = repo.list(tid, open, doctorId, dayStart, limit + 1, offset);
        boolean more = rows.size() > limit;
        List<Item> items = new ArrayList<>();
        LocalDate today = clock.clinicDate(tid);
        for (BookingRequestRepository.ListRow r : rows.subList(0, Math.min(limit, rows.size()))) {
            Req q = r.req();
            Instant alt = open ? alternatives.nearest(tid, q.doctorId(), q.start()).orElse(null) : null;
            items.add(new Item(q.id(), new Patient(r.patientName(), "new".equals(q.visitType()), (int) ChronoUnit.YEARS.between(r.dob(), today)),
                    q.doctorId(), q.start(), q.channel(), q.reviewReason(), q.triageFlags(), q.slaDueAt(), alt, q.status()));
        }
        BookingRequestRepository.Stats s = repo.stats(tid, dayStart);
        return new Page(items, more ? String.valueOf(offset + limit) : null, new Stats(s.autoAccepted(), s.medianReplySeconds()));
    }

    @Transactional
    public Map<String, Object> accept(UUID staffId, UUID id, Instant slotStart) {
        UUID tid = tenantContext.current();
        Req r = repo.find(tid, id).orElseThrow(ApiException::notFound);
        if (!"requested".equals(r.status())) throw alreadyDecided();
        Instant now = clock.now();
        boolean moved = slotStart != null && !slotStart.equals(r.start());
        Instant start = moved ? slotStart : r.start();
        int minutes;
        try {
            minutes = slotValidator.validate(tid, r.doctorId(), start, now);
        } catch (ApiException e) {
            if (moved) throw e; // reception picked a bad time: say which rule it breaks
            throw slotTaken(tid, r); // the held slot has lapsed
        }
        Instant end = start.plus(minutes, ChronoUnit.MINUTES);
        if (repo.overlaps(tid, r.doctorId(), start, end, id)) throw slotTaken(tid, r);
        if (moved && !sameSession(tid, r.doctorId(), r.start(), start)) {
            queueRepo.lockDoctorDay(tid, r.doctorId(), clock.clinicDate(tid, start));
            appointments.enforceSessionCapacity(tid, r.doctorId(), start);
        }
        try {
            if (repo.decide(tid, id, "scheduled", start, end, staffId, now) == 0) throw alreadyDecided();
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw SlotValidator.slotTakenOrRethrow(e); // lost a race on the new slot after the check above
        }
        finishConfirm(tid, staffId, r, start, "booking_request.accepted");
        return Map.of("appointmentId", id, "status", "CONFIRMED");
    }

    @Transactional
    public Map<String, Object> suggest(UUID staffId, UUID id, List<Instant> slots) {
        UUID tid = tenantContext.current();
        if (slots == null || slots.isEmpty() || slots.size() > 3 || slots.stream().anyMatch(java.util.Objects::isNull) || slots.stream().distinct().count() != slots.size()) {
            throw invalid("slots: one to three different times.");
        }
        Req r = repo.find(tid, id).orElseThrow(ApiException::notFound);
        if (!"requested".equals(r.status())) throw alreadyDecided();
        Instant now = clock.now();
        for (Instant s : slots) {
            int minutes = slotValidator.validate(tid, r.doctorId(), s, now);
            if (repo.overlaps(tid, r.doctorId(), s, s.plus(minutes, ChronoUnit.MINUTES), id)) throw slotTaken(tid, r);
        }
        Instant expires = now.plus(OFFER_TTL);
        UUID offerId = repo.insertOffer(tid, id, slots, expires, staffId);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("offerId", offerId);
        extra.put("slots", slots);
        extra.put("expiresAt", expires);
        routed(tid, id, r.doctorId(), r.start(), "suggested", extra);
        recordStaff(tid, staffId, "booking_request.suggested", id, Map.of("offerId", offerId, "slotCount", slots.size()));
        return Map.of("offerId", offerId, "expiresAt", expires);
    }

    @Transactional
    public Map<String, Object> decline(UUID staffId, UUID id, String reasonCode) {
        UUID tid = tenantContext.current();
        if (reasonCode == null || !reasonCode.matches("[a-z0-9_]{3,40}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                    "reasonCode is required to decline (lower-case letters, digits and _, 3 to 40 characters).", Map.of("code", "REASON_REQUIRED"));
        }
        Req r = repo.find(tid, id).orElseThrow(ApiException::notFound);
        if (!"requested".equals(r.status())) throw alreadyDecided();
        if (repo.decide(tid, id, "declined", r.start(), r.end(), staffId, clock.now()) == 0) throw alreadyDecided();
        repo.recordDecline(tid, id, reasonCode);
        notifications.cancelBySubject("appointment", id, com.nabd.hms.shared.notify.CancelReason.SUBJECT_CHANGED);
        waitlist.onSlotFreed(tid, r.doctorId(), r.start());
        routed(tid, id, r.doctorId(), r.start(), "declined", Map.of("reasonCode", reasonCode, "decisionSource", "staff", "decidedBy", staffId));
        recordStaff(tid, staffId, "booking_request.declined", id, Map.of("reasonCode", reasonCode));
        return Map.of("status", "DECLINED");
    }

    // ---- ACC-010: the patient taps one of the offered slots (or "none of these") ------------------------------------------

    /** slotStart null = "none of these": the offer is closed and the request stays open for reception. */
    Map<String, Object> choose(UUID tid, ServiceIdentity edge, UUID offerId, String patientRef, Instant slotStart) {
        return tenantScope.run(tid, () -> {
            UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(BookingRequestService::notOwner);
            BookingRequestRepository.Offer offer = repo.findOffer(tid, offerId).orElseThrow(ApiException::notFound);
            Req r = repo.find(tid, offer.appointmentId()).orElseThrow(ApiException::notFound);
            if (!r.patientId().equals(patientId)) throw notOwner();
            Instant now = clock.now();
            if (!"open".equals(offer.status()) || !"requested".equals(r.status()) || !offer.expiresAt().isAfter(now)) throw alreadyDecided();
            if (slotStart == null) {
                repo.closeOffer(tid, offerId, "none", null);
                record(tid, edge, "booking_request.offer_declined", r.id(), Map.of("patientRef", patientRef, "offerId", offerId));
                return Map.of("appointmentId", r.id(), "status", "REQUESTED");
            }
            if (!offer.slots().contains(slotStart)) throw invalid("slotStart must be one of the offered times.");
            int minutes = slotValidator.validate(tid, r.doctorId(), slotStart, now);
            Instant end = slotStart.plus(minutes, ChronoUnit.MINUTES);
            if (repo.overlaps(tid, r.doctorId(), slotStart, end, r.id())) throw slotTaken(tid, r);
            if (repo.closeOffer(tid, offerId, "chosen", slotStart) == 0) throw alreadyDecided();
            try {
                if (repo.decide(tid, r.id(), "scheduled", slotStart, end, offer.createdBy(), now) == 0) throw alreadyDecided();
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                throw SlotValidator.slotTakenOrRethrow(e);
            }
            repo.closeOffers(tid, r.id());
            finishConfirm(tid, offer.createdBy(), r, slotStart, "booking_request.offer_chosen");
            waitlist.onSlotFreed(tid, r.doctorId(), r.start()); // the original hold is released
            return Map.of("appointmentId", r.id(), "status", "CONFIRMED");
        });
    }

    // ---- ACC-009: past its SLA ---------------------------------------------------------------------------------------------

    /** Raises one escalation per overdue request of the bound tenant (CAS on escalated_at). Returns how many. */
    @Transactional(propagation = Propagation.MANDATORY)
    int escalateDue() {
        UUID tid = tenantContext.current();
        Instant now = clock.now();
        int n = 0;
        for (Req r : repo.dueForEscalation(tid, now, 100)) {
            if (!repo.markEscalated(tid, r.id(), now)) continue;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reviewerRole", "receptionist");
            extra.put("notifyOwner", true);
            extra.put("slaDueAt", r.slaDueAt());
            routed(tid, r.id(), r.doctorId(), r.start(), "escalated", extra);
            audit.record(tid, "system", null, "booking sla", "scheduler", null, "booking_request.escalated", "appointment", r.id(), null, Map.of("slaDueAt", r.slaDueAt()));
            n++;
        }
        return n;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    void finishConfirm(UUID tid, UUID staffId, Req r, Instant start, String auditAction) {
        publishBooked(tid, r.id(), r.doctorId(), start, "scheduled");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("channel", r.channel());
        extra.put("decisionSource", "staff");
        if (staffId != null) extra.put("decidedBy", staffId);
        routed(tid, r.id(), r.doctorId(), start, "confirmed", extra);
        if (staffId != null) recordStaff(tid, staffId, auditAction, r.id(), Map.of("moved", !start.equals(r.start())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    boolean sameSession(UUID tid, UUID doctorId, Instant a, Instant b) {
        if (!clock.clinicDate(tid, a).equals(clock.clinicDate(tid, b))) return false;
        var ba = scheduleRepo.blockAt(tid, doctorId, a);
        var bb = scheduleRepo.blockAt(tid, doctorId, b);
        return ba.isPresent() && bb.isPresent() && ba.get().id().equals(bb.get().id());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    ApiException slotTaken(UUID tid, Req r) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("code", "SLOT_TAKEN");
        alternatives.nearest(tid, r.doctorId(), r.start()).ifPresent(a -> extra.put("nearestAlternative", a.toString()));
        return new ApiException(HttpStatus.CONFLICT, "slot-taken", "Slot no longer available", "That slot is not available.", extra);
    }

    private void publishBooked(UUID tid, UUID id, UUID doctorId, Instant start, String status) {
        events.publish(tid, DomainEventType.APPOINTMENT_BOOKED, id, Map.of("appointmentId", id, "doctorId", doctorId, "startTime", start, "status", status));
    }

    private void routed(UUID tid, UUID id, UUID doctorId, Instant start, String outcome, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", id);
        data.put("doctorId", doctorId);
        data.put("startTime", start);
        data.put("outcome", outcome);
        data.putAll(extra);
        events.publish(tid, DomainEventType.BOOKING_ROUTED, id, data);
    }

    private void record(UUID tid, ServiceIdentity edge, String action, UUID id, Map<String, Object> details) {
        audit.record(tid, "system", null, InternalSchedulingService.ACTOR, edge.serviceId(), null, action, "appointment", id, null, details);
    }

    private void recordStaff(UUID tid, UUID staffId, String action, UUID id, Map<String, Object> details) {
        var actor = actors.findActor(tid, staffId).orElseThrow();
        audit.record(tid, "staff", staffId, actor.name(), actor.role(), null, action, "appointment", id, null, details);
    }

    private static int offsetOf(String cursor) {
        try {
            return cursor == null ? 0 : Math.max(0, Integer.parseInt(cursor)); // shortcut: offset cursor, upgrade to keyset if inboxes outgrow a few hundred rows
        } catch (NumberFormatException e) {
            throw invalid("cursor is not valid.");
        }
    }

    private static ApiException alreadyDecided() {
        return new ApiException(HttpStatus.CONFLICT, "already-decided", "Already decided", "This request was already decided.", Map.of("code", "ALREADY_DECIDED"));
    }

    private static ApiException notOwner() {
        return new ApiException(HttpStatus.FORBIDDEN, "not-owner", "Not the patient's appointment", "That appointment does not belong to this patient.", Map.of("code", "NOT_OWNER"));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
