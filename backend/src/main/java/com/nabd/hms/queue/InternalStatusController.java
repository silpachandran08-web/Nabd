package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.PatientRefs;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ACC-044: what the WhatsApp 'status' reply and the leave-now rules need, for the household of a verified phone: today's open
 * appointments with the ETA shaped by the doctor's display mode (ACC-018). p50 is for the Edge's timing rules and is never shown
 * to a patient unless the mode allows (the Edge shows {@code text}).
 */
@RestController
class InternalStatusController {

    @Schema(name = "InternalVisitStatus")
    public record Item(UUID appointmentId, String patientRef, String status, String mode, String text, boolean leaveHomeAlert, Instant p50) {
    }

    @Schema(name = "InternalVisitStatusList")
    public record Items(List<Item> items) {
    }

    private final TenantScope tenantScope;
    private final PatientRefs refs;
    private final AppointmentRepository appointments;
    private final EtaService eta;
    private final EtaRepository etaRepo;
    private final EtaDisplayModes displayModes;
    private final ClinicClock clock;

    InternalStatusController(TenantScope tenantScope, PatientRefs refs, AppointmentRepository appointments, EtaService eta,
                             EtaRepository etaRepo, EtaDisplayModes displayModes, ClinicClock clock) {
        this.tenantScope = tenantScope;
        this.refs = refs;
        this.appointments = appointments;
        this.eta = eta;
        this.etaRepo = etaRepo;
        this.displayModes = displayModes;
        this.clock = clock;
    }

    @GetMapping("/internal/v1/tenants/{tid}/status/{phoneHash}")
    Items status(@PathVariable UUID tid, @PathVariable String phoneHash) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        if (!phoneHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "phoneHash must be 64 lowercase hex characters (SHA-256).");
        }
        return tenantScope.run(tid, () -> {
            var zone = clock.zone(tid);
            LocalDate today = clock.today(tid);
            List<Item> out = new ArrayList<>();
            for (PatientRefs.HouseholdMember m : refs.household(phoneHash)) {
                UUID patientId = refs.patientIdFor(m.patientRef()).orElse(null);
                if (patientId == null) continue;
                for (var a : appointments.listPage(tid, null, patientId, today, zone, 20, null, null)) {
                    if (!List.of("scheduled", "arrived").contains(a.status())) continue;
                    var r = eta.day(a.doctorId(), today).results().stream().filter(x -> a.id().equals(x.appointmentId())).findFirst().orElse(null);
                    var mode = displayModes.resolve(a.doctorId()).mode();
                    var view = PatientEtaView.shape(mode, new PatientEtaView.Input(r == null ? null : r.p50At(), r == null ? null : r.p80At(),
                            r == null ? null : r.position(), etaRepo.activeDelayMinutes(tid, a.doctorId(), today)), zone);
                    out.add(new Item(a.id(), m.patientRef(), a.status(), view.mode(), view.text(), view.leaveHomeAlert(), r != null && view.leaveHomeAlert() ? r.p50At() : null)); // delay-only / hidden: no time leaves Core
                }
            }
            return new Items(out);
        });
    }
}
