package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.organization.api.ClinicPolicies;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
import com.nabd.hms.queue.dto.CancelRequest;
import com.nabd.hms.queue.dto.RescheduleRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.AppointmentRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/**
 * ACC-038: what the Patient Access Edge may do to scheduling: read availability, book, and confirm / reschedule / cancel on a
 * patient's behalf. Every method runs in a transaction bound to the path tenant (TenantScope) and reuses AppointmentService, so
 * the slot rules, the exclusion constraint (SLOT_TAKEN), events and reminders are exactly the staff ones. The audit actor is
 * "patient via edge" with the Edge service id as role and the opaque patientRef in the details; no patient id or name is written.
 */
@Service
class InternalSchedulingService {

    static final String ACTOR = "patient via edge";
    private static final int MAX_DAYS = 14;

    private final TenantScope tenantScope;
    private final AppointmentService appointments;
    private final AppointmentRepository appointmentRepo;
    private final ScheduleRepository scheduleRepo;
    private final PatientRefs patientRefs;
    private final ClinicPolicies policies;
    private final AuditService audit;
    private final ClinicClock clock;
    private final com.nabd.hms.queue.api.ReasonCodes reasons;
    private final DeskExceptions desk;
    private final PatientConfirmationRepository confirmations;
    private final com.nabd.hms.patient.api.ProxyAccess proxy; // IDC-011

    InternalSchedulingService(TenantScope tenantScope, AppointmentService appointments, AppointmentRepository appointmentRepo,
                              ScheduleRepository scheduleRepo, PatientRefs patientRefs, ClinicPolicies policies, AuditService audit,
                              ClinicClock clock, com.nabd.hms.queue.api.ReasonCodes reasons, DeskExceptions desk,
                              PatientConfirmationRepository confirmations,
                              com.nabd.hms.patient.api.ProxyAccess proxy) {
        this.reasons = reasons;
        this.desk = desk;
        this.proxy = proxy;
        this.tenantScope = tenantScope;
        this.appointments = appointments;
        this.appointmentRepo = appointmentRepo;
        this.scheduleRepo = scheduleRepo;
        this.patientRefs = patientRefs;
        this.policies = policies;
        this.audit = audit;
        this.clock = clock;
        this.confirmations = confirmations;
    }

    @Schema(name = "InternalSession")
    public record Session(String id, LocalDate date, String mode, List<Instant> slots, Integer tokensLeft) {
    }

    @Schema(name = "InternalDoctorSessions")
    public record DoctorSessions(UUID doctorId, List<Session> sessions) {
    }

    @Schema(name = "InternalAvailability")
    public record Availability(List<DoctorSessions> doctors, long v) {
    }

    /** One session per working-hours block per day: "slots" lists the free slot starts, "tokens" a capped session's places left. */
    Availability availability(UUID tid, UUID doctorId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "from and to are required, to is not before from, and the range is at most " + MAX_DAYS + " days.");
        }
        return tenantScope.run(tid, () -> {
            List<UUID> doctors = doctorId != null ? List.of(doctorId) : scheduleRepo.doctorsWithHours(tid);
            ZoneId zone = clock.zone(tid);
            Instant now = clock.now();
            List<DoctorSessions> out = new ArrayList<>();
            for (UUID d : doctors) {
                List<Session> sessions = new ArrayList<>();
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                    if (scheduleRepo.isClinicHoliday(tid, day) || scheduleRepo.isOnLeave(d, day)) continue;
                    Set<Instant> booked = new HashSet<>(scheduleRepo.bookedStartTimes(d, clock.dayStart(tid, day), clock.dayStart(tid, day.plusDays(1))));
                    List<int[]> leave = scheduleRepo.partialLeaves(tid, d, day); // ACC-005
                    var shortDay = scheduleRepo.shortWindows(tid, day); // ADM-028
                    for (WorkingHoursRow wh : scheduleRepo.findForDay(d, day.getDayOfWeek().getValue() % 7)) {
                        String id = wh.id() + "@" + day;
                        if (wh.maxPatients() != null && !SessionModes.HYBRID.equals(SessionModes.modeOn(wh, day))) {
                            int used = scheduleRepo.countSessionOccupancy(tid, d, day, wh);
                            int left = wh.maxPatients() - used;
                            if (wh.onlineCap() != null) { // ACC-011: online places are what the Edge may sell
                                left = Math.min(left, wh.onlineCap() - scheduleRepo.countSessionBookings(tid, d, day, wh));
                            }
                            sessions.add(new Session(id, day, "tokens", null, Math.max(0, left)));
                            continue;
                        }
                        Set<Integer> reserved = scheduleRepo.reservedMinutes(wh.id()); // ACC-012: never offered online
                        List<Instant> slots = new ArrayList<>();
                        Instant start = clock.atSecond(tid, day, wh.startSecond());
                        Instant end = clock.atSecond(tid, day, wh.endSecond());
                        for (; !start.plus(wh.slotMinutes(), ChronoUnit.MINUTES).isAfter(end); start = start.plus(wh.slotMinutes(), ChronoUnit.MINUTES)) {
                            var local = start.atZone(zone).toLocalTime();
                            if (start.isAfter(now) && !booked.contains(start)
                                    && ScheduleRepository.inWindows(shortDay, local, local.plusMinutes(wh.slotMinutes()))
                                    && !reserved.contains(ScheduleGrid.minuteOf(start.atZone(zone).toLocalTime()))
                                    && !ScheduleRepository.overlapsLeave(leave, clock.secondOfClinicDay(tid, day, start), wh.slotMinutes() * 60)) slots.add(start);
                        }
                        sessions.add(new Session(id, day, "slots", slots, null));
                    }
                }
                out.add(new DoctorSessions(d, sessions));
            }
            return new Availability(out, now.toEpochMilli());
        });
    }

    /** actedByRef (IDC-011): the caregiver or guardian who booked for the patient; 403 not-a-proxy without a live relation. */
    Map<String, Object> book(UUID tid, ServiceIdentity edge, String patientRef, UUID doctorId, Instant startTime, String actedByRef) {
        return tenantScope.run(tid, () -> {
            UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(InternalSchedulingService::notOwner);
            var by = actedByRef == null ? null : proxy.resolve(actedByRef, patientId);
            AppointmentResponse a = appointments.book(tid, null, new AppointmentWriteRequest(patientId, doctorId, startTime, false,
                    by == null ? null : by.patientId(), by == null ? null : by.relation()));
            record(tid, edge, "appointment.booked_via_edge", a.id(), patientRef);
            return Map.of("appointmentId", a.id(), "status", a.status());
        });
    }

    Map<String, Object> act(UUID tid, ServiceIdentity edge, UUID id, String action, String patientRef, Instant newStartTime, String reasonCode, String note, String actedByRef) {
        if (!Set.of("confirm", "reschedule", "cancel", "call-me").contains(action)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
        }
        if ("cancel".equals(action) && (reasonCode == null || !reasonCode.matches("[a-z0-9_]{3,40}"))) {
            throw invalid("reasonCode is required to cancel (lower-case letters, digits and _, 3 to 40 characters).");
        }
        if ("reschedule".equals(action) && newStartTime == null) throw invalid("newStartTime is required to reschedule.");
        return tenantScope.run(tid, () -> {
            UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(InternalSchedulingService::notOwner);
            AppointmentRow a = appointmentRepo.findById(tid, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
            if (!a.patientId().equals(patientId)) throw notOwner();
            if ("arrived".equals(a.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "appointment-arrived", "Appointment already arrived",
                        "The patient has checked in; the appointment can no longer be changed.", Map.of("code", "APPOINTMENT_ARRIVED"));
            }
            if (!"scheduled".equals(a.status())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
            }
            var by = actedByRef == null ? null : proxy.resolve(actedByRef, patientId);
            UUID resultId = id;
            String status = "scheduled";
            if ("call-me".equals(action)) { // ACC-041: the patient asks the desk to ring instead of changing inside the window
                confirmations.callMe(tid, id);
                record(tid, edge, "appointment.call_me_requested_via_edge", id, patientRef);
                return Map.of("appointmentId", id, "status", status, "deskException", "open");
            }
            if ("confirm".equals(action)) { // ACC-041: single owner of appointments.patient_confirmed_at
                desk.onConfirmed(tid, id); // ACC-032: the patient answered, the 'unconfirmed' item goes
                Instant at = confirmations.confirm(tid, id);
                record(tid, edge, "appointment.confirmed_via_edge", id, patientRef);
                return Map.of("appointmentId", id, "status", status, "patientConfirmedAt", at);
            }
            if (!"confirm".equals(action)) {
                requireOutsideWindow(tid, a.startTime());
                if ("cancel".equals(action)) {
                    appointments.cancel(tid, null, id, new CancelRequest(reasonCode, note, null));
                    status = "cancelled";
                } else {
                    resultId = appointments.reschedule(tid, null, id, new RescheduleRequest(newStartTime)).id();
                }
                if (by != null) { // IDC-011: the new row of a reschedule carries who moved it
                    if (!"cancel".equals(action)) appointmentRepo.stampActedBy(tid, resultId, by.patientId(), by.relation());
                    proxy.audit(by, action, "appointment", resultId);
                }
            }
            record(tid, edge, "appointment." + ("confirm".equals(action) ? "confirmed" : "cancel".equals(action) ? "cancelled" : "rescheduled") + "_via_edge", id, patientRef);
            return Map.of("appointmentId", resultId, "status", status);
        });
    }

    java.util.List<com.nabd.hms.queue.api.ReasonCodes.Item> reasonCodes(UUID tid, String kind, String locale) {
        return tenantScope.run(tid, () -> reasons.list(kind, locale));
    }

    /** ACC-041: what the Edge needs to offer a reschedule or explain the cancellation window; the patient must own the appointment. */
    Map<String, Object> detail(UUID tid, UUID id, String patientRef) {
        return tenantScope.run(tid, () -> {
            UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(InternalSchedulingService::notOwner);
            AppointmentRow a = appointmentRepo.findById(tid, id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
            if (!a.patientId().equals(patientId)) throw notOwner();
            return Map.of("appointmentId", id, "doctorId", a.doctorId(), "startTime", a.startTime(), "status", a.status(),
                    "zone", clock.zone(tid).getId(), "windowHours", windowHours(tid));
        });
    }

    private long windowHours(UUID tid) {
        return policies.policyValue(tid, "cancellation_window_hours").map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    private void requireOutsideWindow(UUID tid, Instant start) {
        long hours = windowHours(tid);
        if (hours > 0 && Duration.between(clock.now(), start).compareTo(Duration.ofHours(hours)) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "inside-cancellation-window", "Inside the cancellation window",
                    "This appointment starts within the clinic's cancellation window; the clinic must be asked directly.",
                    Map.of("code", "INSIDE_CANCELLATION_WINDOW", "windowHours", hours));
        }
    }

    private void record(UUID tid, ServiceIdentity edge, String action, UUID appointmentId, String patientRef) {
        audit.record(tid, "system", null, ACTOR, edge.serviceId(), null, action, "appointment", appointmentId, null, Map.of("patientRef", patientRef));
    }

    private static ApiException notOwner() { // an unknown reference answers like a foreign one: no way to probe which references exist
        return new ApiException(HttpStatus.FORBIDDEN, "not-owner", "Not the patient's appointment", "That appointment does not belong to this patient.", Map.of("code", "NOT_OWNER"));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
