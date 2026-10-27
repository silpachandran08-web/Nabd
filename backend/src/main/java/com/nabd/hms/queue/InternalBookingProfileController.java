package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.iam.api.InternalCaller;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ACC-040: what the WhatsApp booking flow shows around the slots: clinic name, clinic time zone and the bookable doctors' names (staff data, no patient data). */
@RestController
class InternalBookingProfileController {

    @Schema(name = "InternalProfileDoctor")
    record Doctor(UUID doctorId, String name) {
    }

    @Schema(name = "InternalBookingProfile")
    record Profile(String clinicName, String timeZone, List<Doctor> doctors) {
    }

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final ScheduleRepository schedule;
    private final ActorDirectory actors;
    private final ClinicClock clock;

    InternalBookingProfileController(TenantScope tenantScope, JdbcTemplate jdbc, ScheduleRepository schedule, ActorDirectory actors, ClinicClock clock) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.schedule = schedule;
        this.actors = actors;
        this.clock = clock;
    }

    @GetMapping("/internal/v1/tenants/{tid}/booking-profile")
    Profile profile(@PathVariable UUID tid) {
        if (!InternalCaller.current().mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        return tenantScope.run(tid, () -> {
            var names = actors.findActors(tid, schedule.doctorsWithHours(tid));
            return new Profile(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tid), clock.zone(tid).getId(),
                    names.values().stream().sorted(java.util.Comparator.comparing(ActorDirectory.Actor::name)).map(a -> new Doctor(a.id(), a.name())).toList());
        });
    }
}
