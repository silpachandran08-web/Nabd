package com.nabd.hms.queue;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.DoctorLeaveResponse;
import com.nabd.hms.queue.dto.DoctorLeaveWriteRequest;
import com.nabd.hms.queue.dto.WorkingHoursResponse;
import com.nabd.hms.queue.dto.WorkingHoursWriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    // ponytail: slot generation is UTC-based, no per-region timezone or prayer-time blocking yet
    // (NB-074's regional prayer-time blocks). Add once a tenant carries a timezone/region setting
    // the slot generator can read.
    private static final ZoneOffset ZONE = ZoneOffset.UTC;

    private final ScheduleRepository repo;
    private final TenantContext tenantContext;

    ScheduleService(ScheduleRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<WorkingHoursResponse> listWorkingHours(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.listWorkingHours(doctorId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public WorkingHoursResponse addWorkingHours(UUID tenantId, UUID callerStaffId, UUID doctorId, WorkingHoursWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertWorkingHours(tenantId, doctorId, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
        log.info("working hours {} added for doctor {} by {} (day {}, {}-{}, cap {})",
                id, doctorId, callerStaffId, req.dayOfWeek(), req.startTime(), req.endTime(), req.maxPatients());
        return new WorkingHoursResponse(id, req.dayOfWeek(), req.startTime(), req.endTime(),
                req.slotMinutesOrDefault(), req.maxPatients());
    }

    @Transactional
    public List<DoctorLeaveResponse> listLeave(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.listLeave(doctorId).stream()
                .map(l -> new DoctorLeaveResponse(l.id(), l.dateFrom(), l.dateTo(), l.reason()))
                .toList();
    }

    @Transactional
    public DoctorLeaveResponse addLeave(UUID tenantId, UUID callerStaffId, UUID doctorId, DoctorLeaveWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insertLeave(tenantId, doctorId, req.dateFrom(), req.dateTo(), req.reason());
        log.info("leave {} added for doctor {} by {} ({} to {})", id, doctorId, callerStaffId, req.dateFrom(), req.dateTo());
        return new DoctorLeaveResponse(id, req.dateFrom(), req.dateTo(), req.reason());
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

        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        Set<Instant> booked = new HashSet<>(repo.bookedStartTimes(doctorId, dayStart, dayEnd));

        Instant now = Instant.now();
        List<Instant> slots = new ArrayList<>();
        for (WorkingHoursRow wh : hours) {
            Instant slotStart = date.atTime(wh.startTime()).atZone(ZONE).toInstant();
            Instant blockEnd = date.atTime(wh.endTime()).atZone(ZONE).toInstant();
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
}
