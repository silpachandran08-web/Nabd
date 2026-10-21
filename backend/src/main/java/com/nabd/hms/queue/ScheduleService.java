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

    ScheduleService(ScheduleRepository repo, TenantContext tenantContext, AuditService auditService, ClinicClock clock,
                    com.nabd.hms.common.EventPublisher events, ActorDirectory actors) {
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
        UUID id = repo.insertWorkingHours(tenantId, doctorId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
        log.info("working hours {} added for doctor {} by {} (day {}, {}-{}, cap {})",
                id, doctorId, callerStaffId, req.dayOfWeek(), req.startTime(), req.endTime(), req.maxPatients());
        WorkingHoursResponse created = new WorkingHoursResponse(id, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
        auditHours(tenantId, callerStaffId, "schedule.working_hours_created", id, null, auditView(doctorId, created));
        return created;
    }

    @Transactional
    public WorkingHoursResponse updateWorkingHours(UUID tenantId, UUID callerStaffId, UUID doctorId, UUID hoursId,
                                                   WorkingHoursWriteRequest req) {
        tenantContext.set(tenantId);
        WorkingHoursResponse before = toResponse(repo.findWorkingHours(doctorId, hoursId).orElseThrow(this::hoursNotFound));
        checkHours(doctorId, hoursId, req);
        repo.updateWorkingHours(doctorId, hoursId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
        WorkingHoursResponse after = new WorkingHoursResponse(hoursId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
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

    /** DOC-R7: start before end, and no overlap with the doctor's other blocks on that weekday. */
    private void checkHours(UUID doctorId, UUID exceptHoursId, WorkingHoursWriteRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-time-range", "Invalid time range",
                    "End time must be after start time.");
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
                .map(l -> new DoctorLeaveResponse(l.id(), l.dateFrom(), l.dateTo(), l.reason(), List.of()))
                .toList();
    }

    @Transactional
    public DoctorLeaveResponse addLeave(UUID tenantId, UUID callerStaffId, UUID doctorId, DoctorLeaveWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertLeave(tenantId, doctorId, req.dateFrom(), req.dateTo(), req.reason());
        List<String> affected = repo.findAffectedPackagePatientNames(tenantId, doctorId);
        log.info("leave {} added for doctor {} by {} ({} to {}); {} package patient(s) potentially affected",
                id, doctorId, callerStaffId, req.dateFrom(), req.dateTo(), affected.size());
        return new DoctorLeaveResponse(id, req.dateFrom(), req.dateTo(), req.reason(), affected);
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
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Set<Instant> booked = new HashSet<>(repo.bookedStartTimes(doctorId, dayStart, dayEnd));

        Instant now = clock.now();
        List<Instant> slots = new ArrayList<>();
        for (WorkingHoursRow wh : hours) {
            Instant slotStart = date.atTime(wh.startTime()).atZone(zone).toInstant();
            Instant blockEnd = date.atTime(wh.endTime()).atZone(zone).toInstant();
            while (!slotStart.plus(wh.slotMinutes(), ChronoUnit.MINUTES).isAfter(blockEnd)) {
                if (slotStart.isAfter(now) && !booked.contains(slotStart)) {
                    slots.add(slotStart);
                }
                slotStart = slotStart.plus(wh.slotMinutes(), ChronoUnit.MINUTES);
            }
        }
        return slots;
    }

    private WorkingHoursResponse toResponse(WorkingHoursRow row) {
        return new WorkingHoursResponse(row.id(), row.dayOfWeek(), row.startTime(), row.endTime(),
                row.slotMinutes(), row.maxPatients());
    }

    // ── delay ladder (NB-100) ────────────────────────────────────────────
    // The patient-facing broadcast (and its "bypasses the prayer-window hold" AC) needs E17's
    // messaging infrastructure — not built yet, deferred like every other WhatsApp-dependent
    // ticket this session. This is the in-app half: announce/clear plus history, "operationally
    // urgent" here meaning it takes effect immediately, with nothing to hold it in the first place.

    @Transactional
    public DoctorDelayResponse announceDelay(UUID tenantId, UUID callerStaffId, UUID doctorId, DelayAnnounceRequest req) {
        tenantContext.set(tenantId);
        LocalDate today = clock.today(tenantId);
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
        LocalDate today = clock.today(tenantId);
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
    }

    @Transactional
    public List<DoctorDelayResponse> delayHistory(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        LocalDate today = clock.today(tenantId);
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
