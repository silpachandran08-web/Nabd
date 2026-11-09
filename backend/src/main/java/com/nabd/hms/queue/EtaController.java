package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.queue.EtaEngine.Result;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-017: ETA ranges for a doctor-day (staff) and for one appointment (Patient Access Edge). Reads the projection; no names, no phone numbers. */
@RestController
public class EtaController {

    @Schema(name = "EtaItem")
    public record Item(UUID queueEntryId, UUID appointmentId, int position, Instant p50At, Instant p80At, String rangeLabel) {
    }

    @Schema(name = "EtaResponse")
    public record Response(long v, List<Item> items) {
    }

    @Schema(name = "InternalEtaResponse")
    public record Internal(int position, Instant p50At, Instant p80At, Map<String, Object> display, long v) {
    }

    private final EtaService eta;
    private final TenantContext tenantContext;
    private final TenantScope tenantScope;
    private final ClinicClock clock;
    private final AppointmentRepository appointments;

    EtaController(EtaService eta, TenantContext tenantContext, TenantScope tenantScope, ClinicClock clock, AppointmentRepository appointments) {
        this.eta = eta;
        this.tenantContext = tenantContext;
        this.tenantScope = tenantScope;
        this.clock = clock;
        this.appointments = appointments;
    }

    @GetMapping("/v1/queue/eta")
    @PreAuthorize("hasAuthority('queue:view')")
    @Transactional
    public Response get(@RequestParam UUID doctorId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID tid = tenantContext.current();
        EtaService.Day d = eta.day(doctorId, date != null ? date : clock.clinicDate(tid));
        return new Response(d.v(), d.results().stream()
                .map(r -> new Item(r.entryId(), r.appointmentId(), r.position(), r.p50At(), r.p80At(), eta.label(r))).toList());
    }

    /** Edge shape: the text parameters are clinic-local clock times; the Edge picks the wording and the language. */
    @GetMapping("/internal/v1/tenants/{tid}/eta/appointments/{appointmentId}")
    public Internal one(@PathVariable UUID tid, @PathVariable UUID appointmentId) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return tenantScope.run(tid, () -> {
            var appt = appointments.findById(tid, appointmentId).orElseThrow(ApiException::notFound);
            LocalDate day = clock.clinicDate(tid, appt.startTime());
            EtaService.Day d = eta.day(appt.doctorId(), day);
            Result r = d.results().stream().filter(x -> appointmentId.equals(x.appointmentId())).findFirst().orElseThrow(ApiException::notFound);
            String label = eta.label(r);
            return new Internal(r.position(), r.p50At(), r.p80At(),
                    Map.of("mode", "range", "from", label.substring(0, 5), "to", label.substring(6)), d.v());
        });
    }

}
