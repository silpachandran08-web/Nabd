package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/** ACC-005: the clinic-day start (default 04:00) that keeps an evening session running past midnight on one queue date. */
@Service
public class ClinicDayStartService {

    @Schema(name = "ClinicDayStartResponse")
    public record Result(String dayStart, LocalDate effectiveFromClinicDate) {
    }

    private final ScheduleRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final StepUpVerifier stepUp;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;

    ClinicDayStartService(ScheduleRepository repo, TenantContext tenantContext, ClinicClock clock, StepUpVerifier stepUp,
                          AuditService audit, ActorDirectory actors, EventPublisher events) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.stepUp = stepUp;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
    }

    /**
     * shortcut: applies at once (no future-dated switch), so change it away from midnight; the clinic date moves for anyone
     * whose current time falls between the old and the new start. Upgrade with an effective-from date if a clinic needs it.
     */
    @Transactional
    public Result set(UUID callerStaffId, String hhmm, String stepUpToken) {
        UUID tenantId = tenantContext.current();
        int minute = parse(hhmm);
        stepUp.require(stepUpToken, callerStaffId);
        if (repo.overnightEndingAfter(tenantId, minute * 60) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "sessions-conflict", "Sessions conflict",
                    "An existing overnight session ends after that day start.", Map.of("code", "SESSIONS_CONFLICT"));
        }
        int before = clock.dayStartMinute(tenantId);
        repo.setDayStart(tenantId, minute);
        clock.evict(tenantId);
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null, "clinic.day_start_changed",
                "tenant", tenantId, Map.of("dayStartMinute", before), Map.of("dayStartMinute", minute));
        events.publish(tenantId, DomainEventType.ORGANIZATION_CONFIG_CHANGED, tenantId, Map.of("configKey", "clinic.day_start", "status", "updated"));
        return new Result(String.format("%02d:%02d", minute / 60, minute % 60), clock.clinicDate(tenantId));
    }

    private static int parse(String hhmm) {
        try {
            if (hhmm != null && hhmm.matches("\\d{2}:\\d{2}")) {
                int m = LocalTime.parse(hhmm).toSecondOfDay() / 60;
                if (m <= 359) {
                    return m;
                }
            }
        } catch (java.time.DateTimeException ignored) {
            // falls through to the 400
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid day start", "dayStart must be HH:mm between 00:00 and 05:59.",
                Map.of("code", "VALIDATION"));
    }
}
