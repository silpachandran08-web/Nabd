package com.nabd.hms.queue;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.DelayAnnounceRequest;
import com.nabd.hms.queue.dto.DoctorDelayResponse;
import com.nabd.hms.queue.dto.DoctorLeaveResponse;
import com.nabd.hms.queue.dto.DoctorLeaveWriteRequest;
import com.nabd.hms.queue.dto.WorkingHoursResponse;
import com.nabd.hms.queue.dto.WorkingHoursWriteRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.DoctorDelayRow;
import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    // ponytail: no prayer-time blocking yet (NB-074's regional prayer-time blocks).

    private final ScheduleRepository repo;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    private final ClinicClock clock;

    private final com.nabd.hms.common.EventPublisher events;
    private final ActorDirectory actors;
    private final EtaTrigger etaTrigger;

    ScheduleService(ScheduleRepository repo, TenantContext tenantContext, AuditService auditService, ClinicClock clock,
                    com.nabd.hms.common.EventPublisher events, ActorDirectory actors, EtaTrigger etaTrigger) {
        this.etaTrigger = etaTrigger;
        this.events = events;
        this.actors = actors;
        this.clock = clock;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
    }

    @Transactional
    public List<WorkingHoursResponse> listWorkingHours(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.listWorkingHours(doctorId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public WorkingHoursResponse addWorkingHours(UUID tenantId, UUID callerStaffId, UUID doctorId, WorkingHoursWriteRequest req) {
        tenantContext.set(tenantId);
        checkHours(doctorId, null, req);
        String mode = req.sessionMode() == null ? SessionModes.SLOT : req.sessionMode();
        checkSessionFields(mode, req.maxPatients(), req.onlineCap());
        UUID id = repo.insertWorkingHours(tenantId, doctorId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients(), mode, SessionModes.SLOT, req.onlineCap(), req.label(), req.effectiveFrom());
        List<LocalTime> reserved = saveReserved(tenantId, id, req, req.reservedWalkInTimes());
        repo.updateWalkinRules(doctorId, id, req.walkInMode(), req.walkInRatio(), req.lateGraceMinutes());
        log.info("working hours {} added for doctor {} by {} (day {}, {}-{}, cap {})",
                id, doctorId, callerStaffId, req.dayOfWeek(), req.startTime(), req.endTime(), req.maxPatients());
        WorkingHoursResponse created = toResponse(repo.findWorkingHours(doctorId, id).orElseThrow(), reserved);
        auditHours(tenantId, callerStaffId, "schedule.working_hours_created", id, null, auditView(doctorId, created));
        return created;
    }

    @Transactional
    public WorkingHoursResponse updateWorkingHours(UUID tenantId, UUID callerStaffId, UUID doctorId, UUID hoursId,
                                                   WorkingHoursWriteRequest req) {
        tenantContext.set(tenantId);
        WorkingHoursResponse before = toResponse(repo.findWorkingHours(doctorId, hoursId).orElseThrow(this::hoursNotFound));
        checkHours(doctorId, hoursId, req);
        WorkingHoursRow old = repo.findWorkingHours(doctorId, hoursId).orElseThrow(this::hoursNotFound);
        String mode = req.sessionMode() == null ? old.sessionMode() : req.sessionMode();
        Integer onlineCap = req.onlineCap() == null ? old.onlineCap() : req.onlineCap();
        checkSessionFields(mode, req.maxPatients(), onlineCap);
        String previous = old.previousSessionMode();
        LocalDate effective = old.effectiveFrom();
        if (!mode.equals(old.sessionMode())) { // ACC-011: a mode change applies to future dates only
            LocalDate tomorrow = clock.clinicDate(tenantId).plusDays(1);
            if (req.effectiveFrom() != null && req.effectiveFrom().isBefore(tomorrow)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "effective-from-past", "Change must apply to a future date",
                        "A session mode change takes effect from tomorrow at the earliest.");
            }
            previous = SessionModes.modeOn(old, tomorrow);
            effective = req.effectiveFrom() == null ? tomorrow : req.effectiveFrom();
        }
        repo.updateWorkingHours(doctorId, hoursId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients(), mode, previous, onlineCap,
                req.label() == null ? old.label() : req.label(), effective);
        List<LocalTime> reserved = saveReserved(tenantId, hoursId, req, req.reservedWalkInTimes());
        repo.updateWalkinRules(doctorId, hoursId, req.walkInMode(), req.walkInRatio(), req.lateGraceMinutes());
        WorkingHoursResponse after = toResponse(repo.findWorkingHours(doctorId, hoursId).orElseThrow(), reserved);
        auditHours(tenantId, callerStaffId, "schedule.working_hours_updated", hoursId,
                auditView(doctorId, before), auditView(doctorId, after));
        log.info("working hours {} of doctor {} updated by {}", hoursId, doctorId, callerStaffId);
        return after;
    }

    @Transactional
    public void deleteWorkingHours(UUID tenantId, UUID callerStaffId, UUID doctorId, UUID hoursId) {
        tenantContext.set(tenantId);
        WorkingHoursResponse before = toResponse(repo.findWorkingHours(doctorId, hoursId).orElseThrow(this::hoursNotFound));
        repo.deleteWorkingHours(doctorId, hoursId);
        auditHours(tenantId, callerStaffId, "schedule.working_hours_deleted", hoursId, auditView(doctorId, before), null);
        log.info("working hours {} of doctor {} deleted by {}", hoursId, doctorId, callerStaffId);
    }

    /**
     * DOC-R7: no overlap with the doctor's other blocks on that weekday. ACC-005: end before start means the block runs past midnight
     * (same clinic day), and it must end at or before the tenant's day start; end == start is an empty block.
     */
    private void checkHours(UUID doctorId, UUID exceptHoursId, WorkingHoursWriteRequest req) {
        if (req.endTime().equals(req.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-time-range", "Invalid time range",
                    "End time must differ from start time.");
        }
        if (!req.endTime().isAfter(req.startTime())
                && req.endTime().toSecondOfDay() > clock.dayStartMinute(tenantContext.current()) * 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "session-crosses-day-start", "Session crosses the day start",
                    "An overnight session must end by the clinic day start.", Map.of("code", "SESSION_CROSSES_DAY_START"));
        }
        repo.lockDoctorHours(doctorId);
        if (repo.hoursOverlap(doctorId, req.dayOfWeek(), req.startTime(), req.endTime(), exceptHoursId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "hours-overlap", "Working hours overlap",
                    "These hours overlap another block for this doctor on the same day.");
        }
    }

    private static Map<String, Object> auditView(UUID doctorId, WorkingHoursResponse h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doctorId", doctorId.toString());
        m.put("dayOfWeek", h.dayOfWeek());
        m.put("startTime", h.startTime().toString());
        m.put("endTime", h.endTime().toString());
        m.put("slotMinutes", h.slotMinutes());
        m.put("maxPatients", h.maxPatients());
        m.put("sessionMode", h.sessionMode());
        m.put("onlineCap", h.onlineCap());
        m.put("effectiveFrom", h.effectiveFrom() == null ? null : h.effectiveFrom().toString());
        m.put("walkInMode", h.walkInMode());
        m.put("walkInRatio", h.walkInRatio());
        return m;
    }

    private void auditHours(UUID tenantId, UUID callerStaffId, String action, UUID hoursId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(this::hoursNotFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "doctor_working_hours", hoursId, before, after);
    }

    private ApiException hoursNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    @Transactional
    public List<DoctorLeaveResponse> listLeave(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.listLeave(doctorId).stream()
                .map(l -> new DoctorLeaveResponse(l.id(), l.dateFrom(), l.dateTo(), l.reason(), List.of(), l.startTime(), l.endTime(), List.of()))
                .toList();
    }

    @Transactional
    public DoctorLeaveResponse addLeave(UUID tenantId, UUID callerStaffId, UUID doctorId, DoctorLeaveWriteRequest req) {
        tenantContext.set(tenantId);
        if (!repo.doctorInTenant(tenantId, doctorId)) { // OPS-043: a foreign doctor id must not be stored
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Doctor not found", "No such doctor.");
        }
        checkLeaveWindow(req);
        UUID id = repo.insertLeave(tenantId, doctorId, req.dateFrom(), req.dateTo(), req.reason(), req.startTime(), req.endTime());
        List<String> affected = repo.findAffectedPackagePatientNames(tenantId, doctorId);
        List<DoctorLeaveResponse.AffectedAppointment> bookings = affectedAppointments(tenantId, doctorId, req.dateFrom(), req.dateTo(), req.startTime(), req.endTime());
        repo.flagNeedsReschedule(tenantId, bookings.stream().map(DoctorLeaveResponse.AffectedAppointment::id).toList(), "doctor_leave"); // ACC-006
        log.info("leave {} added for doctor {} by {} ({} to {}); {} package patient(s), {} booking(s) potentially affected",
                id, doctorId, callerStaffId, req.dateFrom(), req.dateTo(), affected.size(), bookings.size());
        return new DoctorLeaveResponse(id, req.dateFrom(), req.dateTo(), req.reason(), affected, req.startTime(), req.endTime(), bookings);
    }

    /** ACC-006: the bookings an existing leave block covers (404 when the leave is not this doctor's). */
    @Transactional
    public List<DoctorLeaveResponse.AffectedAppointment> leaveBookings(UUID tenantId, UUID doctorId, UUID leaveId) { // the caller's request already bound the tenant
        var leave = repo.listLeave(doctorId).stream().filter(l -> l.id().equals(leaveId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        return affectedAppointments(tenantId, doctorId, leave.dateFrom(), leave.dateTo(), leave.startTime(), leave.endTime());
    }

    private static void checkLeaveWindow(DoctorLeaveWriteRequest req) {
        if (req.dateTo().isBefore(req.dateFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid dates", "dateTo cannot be before dateFrom.");
        }
        if ((req.startTime() == null) != (req.endTime() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid leave window", "Give both startTime and endTime, or neither.");
        }
        if (req.startTime() != null && (req.startTime().equals(req.endTime()) || !req.dateFrom().equals(req.dateTo()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid leave window",
                    "Part-day leave covers one clinic day, with an end different from the start.");
        }
    }

    /** ACC-005: the doctor's live bookings the leave covers (all of the clinic days, or only the part-day window). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<DoctorLeaveResponse.AffectedAppointment> affectedAppointments(UUID tenantId, UUID doctorId, LocalDate from, LocalDate to,
                                                                              LocalTime start, LocalTime end) {
        Instant rangeStart = clock.dayStart(tenantId, from);
        Instant rangeEnd = clock.dayStart(tenantId, to.plusDays(1));
        int[] window = start == null ? null : QueueModels.leaveWindow(start, end, clock.dayStartMinute(tenantId) * 60);
        return repo.liveBookingsBetween(tenantId, doctorId, rangeStart, rangeEnd).stream()
                .filter(b -> window == null || inWindow(tenantId, from, (Instant) b[1], window))
                .map(b -> new DoctorLeaveResponse.AffectedAppointment((UUID) b[0], (Instant) b[1]))
                .toList();
    }

    private boolean inWindow(UUID tenantId, LocalDate day, Instant at, int[] window) {
        int second = clock.secondOfClinicDay(tenantId, day, at);
        return second >= window[0] && second < window[1];
    }

    @Transactional
    public List<Instant> availability(UUID tenantId, UUID doctorId, LocalDate date) {
        tenantContext.set(tenantId);
        if (repo.isClinicHoliday(tenantId, date) || repo.isOnLeave(doctorId, date)) {
            return List.of();
        }

        int dayOfWeek = date.getDayOfWeek().getValue() % 7; // Java MON=1..SUN=7 -> Postgres-style SUN=0..SAT=6
        List<WorkingHoursRow> hours = repo.findForDay(doctorId, dayOfWeek);
        if (hours.isEmpty()) {
            return List.of();
        }

        ZoneId zone = clock.zone(tenantId); // "09:00" in working hours is the clinic's wall clock
        Set<Instant> booked = new HashSet<>(repo.bookedStartTimes(doctorId, clock.dayStart(tenantId, date), clock.dayStart(tenantId, date.plusDays(1))));
        List<int[]> leave = repo.partialLeaves(tenantId, doctorId, date); // ACC-005: part-day leave hides the slots it touches

        Instant now = clock.now();
        List<Instant> slots = new ArrayList<>();
        for (WorkingHoursRow wh : hours) {
            if (SessionModes.TOKEN.equals(SessionModes.modeOn(wh, date))) continue; // ACC-011: token sessions sell places, not times
            Set<Integer> reserved = repo.reservedMinutes(wh.id()); // ACC-012: held back for walk-ins, never offered
            Instant slotStart = clock.atSecond(tenantId, date, wh.startSecond());
            Instant blockEnd = clock.atSecond(tenantId, date, wh.endSecond());
            while (!slotStart.plus(wh.slotMinutes(), ChronoUnit.MINUTES).isAfter(blockEnd)) {
                if (slotStart.isAfter(now) && !booked.contains(slotStart)
                        && !reserved.contains(ScheduleGrid.minuteOf(slotStart.atZone(zone).toLocalTime()))
                        && !ScheduleRepository.overlapsLeave(leave, clock.secondOfClinicDay(tenantId, date, slotStart), wh.slotMinutes() * 60)) {
                    slots.add(slotStart);
                }
                slotStart = slotStart.plus(wh.slotMinutes(), ChronoUnit.MINUTES);
            }
        }
        return slots;
    }

    private WorkingHoursResponse toResponse(WorkingHoursRow row) {
        return toResponse(row, reservedTimes(row));
    }

    private WorkingHoursResponse toResponse(WorkingHoursRow row, List<LocalTime> reserved) {
        return new WorkingHoursResponse(row.id(), row.dayOfWeek(), row.startTime(), row.endTime(),
                row.slotMinutes(), row.maxPatients(), row.sessionMode(), row.onlineCap(), row.label(), row.effectiveFrom(), reserved,
                row.walkinMode(), row.walkinRatio(), row.lateGraceMinutes(), row.endsNextDay());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<LocalTime> reservedTimes(WorkingHoursRow row) {
        return repo.reservedMinutes(row.id()).stream().sorted().map(m -> LocalTime.of(m / 60, m % 60)).toList();
    }

    /** ACC-011: token and hybrid sessions sell places from the max_patients pool; the online cap is a share of it. */
    private static void checkSessionFields(String mode, Integer maxPatients, Integer onlineCap) {
        if (!SessionModes.SLOT.equals(mode) && maxPatients == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "max-patients-required", "Session size required",
                    "A token or hybrid session needs maxPatients.");
        }
        if (onlineCap != null && maxPatients != null && onlineCap > maxPatients) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "online-cap-too-large", "Online cap too large",
                    "The online cap cannot exceed maxPatients.");
        }
    }

    /** ACC-012: reserved walk-in starts must sit on the session grid and inside the session; null = leave as is. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tx
    private List<LocalTime> saveReserved(UUID tenantId, UUID hoursId, WorkingHoursWriteRequest req, List<LocalTime> times) {
        if (times == null) {
            return repo.reservedMinutes(hoursId).stream().sorted().map(m -> LocalTime.of(m / 60, m % 60)).toList();
        }
        Set<Integer> minutes = new java.util.TreeSet<>();
        int startMinute = ScheduleGrid.minuteOf(req.startTime());
        int endMinute = ScheduleGrid.minuteOf(req.endTime()) + (req.endTime().isAfter(req.startTime()) ? 0 : 1440); // ACC-005: overnight
        for (LocalTime t : times) {
            int at = ScheduleGrid.minuteOf(t) < startMinute ? ScheduleGrid.minuteOf(t) + 1440 : ScheduleGrid.minuteOf(t);
            int off = at - startMinute;
            if (t.getSecond() != 0 || off < 0 || off % req.slotMinutesOrDefault() != 0
                    || at + req.slotMinutesOrDefault() > endMinute) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "reserved-off-grid", "Reserved walk-in slot off the grid",
                        "Reserved walk-in times must be slot starts inside the session.");
            }
            minutes.add(ScheduleGrid.minuteOf(t));
        }
        repo.replaceReservedMinutes(tenantId, hoursId, minutes);
        return minutes.stream().map(m -> LocalTime.of(m / 60, m % 60)).toList();
    }

    // ── delay ladder (NB-100) ────────────────────────────────────────────
    // The patient-facing broadcast (and its "bypasses the prayer-window hold" AC) needs E17's
    // messaging infrastructure — not built yet, deferred like every other WhatsApp-dependent
    // ticket this session. This is the in-app half: announce/clear plus history, "operationally
    // urgent" here meaning it takes effect immediately, with nothing to hold it in the first place.

    @Transactional
    public DoctorDelayResponse announceDelay(UUID tenantId, UUID callerStaffId, UUID doctorId, DelayAnnounceRequest req) {
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId);
        if (!repo.doctorInTenant(tenantId, doctorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Doctor not found", "No such doctor.");
        }
        UUID id;
        try {
            id = repo.announceDelay(tenantId, doctorId, req.delayMinutes(), req.reason(), callerStaffId, today);
        } catch (DuplicateKeyException e) { // lost the race on uq_doctor_delays_active_day
            throw new ApiException(HttpStatus.CONFLICT, "delay-already-active", "Delay already active",
                    "Another delay was announced for this doctor at the same moment.");
        }
        log.info("doctor {} delay announced by {}: {} min", doctorId, callerStaffId, req.delayMinutes());
        DoctorDelayRow row = repo.findActiveDelay(doctorId, today).orElseThrow();
        auditDelay(tenantId, callerStaffId, "doctor_delay.announced", id, null, toDelayResponse(row, today));
        delayChanged(tenantId, doctorId, id, req.delayMinutes(), "active");
        return toDelayResponse(row, today);
    }

    @Transactional
    public void clearDelay(UUID tenantId, UUID callerStaffId, UUID doctorId) {
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId);
        DoctorDelayRow active = repo.findActiveDelay(doctorId, today).orElse(null);
        if (active == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-active-delay", "No active delay",
                    "This doctor doesn't have an active delay to clear.");
        }
        repo.clearActiveDelay(tenantId, doctorId, callerStaffId);
        log.info("doctor {} delay cleared by {}", doctorId, callerStaffId);
        auditDelay(tenantId, callerStaffId, "doctor_delay.cleared", active.id(), toDelayResponse(active, today), null);
        delayChanged(tenantId, doctorId, active.id(), 0, "cleared");
    }

    /** OPS-015: the free-text reason stays out of the event. */
    private void delayChanged(UUID tenantId, UUID doctorId, UUID delayId, int delayMinutes, String status) {
        events.publish(tenantId, com.nabd.hms.common.DomainEventType.DOCTOR_DELAY_CHANGED, doctorId, java.util.Map.of(
                "doctorId", doctorId, "delayId", delayId, "delayMinutes", delayMinutes, "status", status));
        etaTrigger.afterCommit(tenantId, doctorId, clock.clinicDate(tenantId)); // ACC-017: every waiting and booked ETA shifts
    }

    @Transactional
    public List<DoctorDelayResponse> delayHistory(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId);
        return repo.delayHistory(doctorId, 20).stream().map(r -> toDelayResponse(r, today)).toList();
    }

    private void auditDelay(UUID tenantId, UUID callerStaffId, String action, UUID delayId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(this::hoursNotFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "doctor_delay", delayId, before, after);
    }

    private DoctorDelayResponse toDelayResponse(DoctorDelayRow row, LocalDate today) {
        return new DoctorDelayResponse(row.id(), row.doctorId(), row.delayMinutes(), row.reason(), row.announcedBy(),
                row.announcedAt(), row.clearedBy(), row.clearedAt(), row.active(today), row.clinicDate());
    }
}
