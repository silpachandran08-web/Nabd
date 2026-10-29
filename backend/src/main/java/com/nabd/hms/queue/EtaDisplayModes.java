package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-018: which ETA display mode a patient of this doctor gets right now: the session block covering this moment, else the
 * doctor's own setting, else the facility default (facility_queue_settings; the ACC-008 booking policy may own it later), else 'range'.
 */
@Service
public class EtaDisplayModes {

    public static final String POLICY_KEY = "eta_display_mode";

    public record Resolved(String mode, String source) {
    }

    public record SessionSetting(UUID workingHoursId, int dayOfWeek, LocalTime startTime, LocalTime endTime, String etaDisplayMode) {
    }

    public record Settings(String etaDisplayMode, String facilityDefault, String effectiveMode, String effectiveSource,
                           List<SessionSetting> sessions) {
    }

    private final EtaDisplayRepository repo;
    private final QueueRepository queueRepo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;

    EtaDisplayModes(EtaDisplayRepository repo, QueueRepository queueRepo, TenantContext tenantContext,
                    ClinicClock clock, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.queueRepo = queueRepo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    /** Runs in the caller's tenant transaction. */
    @Transactional
    public Resolved resolve(UUID doctorId) {
        UUID tenantId = tenantContext.current();
        ZoneId zone = clock.zone(tenantId);
        var now = clock.now().atZone(zone);
        var session = repo.sessionModeAt(tenantId, doctorId, now.getDayOfWeek().getValue() % 7, now.toLocalTime());
        if (session.isPresent()) {
            return new Resolved(session.get(), "session");
        }
        var doctor = repo.doctorMode(tenantId, doctorId);
        if (doctor.isPresent()) {
            return new Resolved(doctor.get(), "doctor");
        }
        var facility = repo.facilityMode(tenantId).filter(PatientEtaView::isMode);
        return facility.map(f -> new Resolved(f, "facility")).orElse(new Resolved(PatientEtaView.DEFAULT_MODE, "default"));
    }

    @Transactional
    public Settings settings(UUID doctorId) {
        UUID tenantId = tenantContext.current();
        requireDoctor(tenantId, doctorId);
        Resolved eff = resolve(doctorId);
        return new Settings(repo.doctorMode(tenantId, doctorId).orElse(null),
                repo.facilityMode(tenantId).filter(PatientEtaView::isMode).orElse(PatientEtaView.DEFAULT_MODE),
                eff.mode(), eff.source(),
                repo.sessions(tenantId, doctorId).stream().map(s -> new SessionSetting(s.workingHoursId(), s.dayOfWeek(), s.startTime(), s.endTime(), s.mode())).toList());
    }

    /** The clinic-wide default (never null: a mode is required). */
    @Transactional
    public Resolved putFacility(UUID callerStaffId, String mode) {
        UUID tenantId = tenantContext.current();
        if (mode == null || !PatientEtaView.isMode(mode)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "out-of-range", "Out of range", "Use exact, range, position, delay_only or hidden.");
        }
        String before = repo.facilityMode(tenantId).orElse(null);
        repo.upsertFacilityMode(tenantId, mode, callerStaffId);
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        Map<String, Object> was = new java.util.HashMap<>();
        was.put("etaDisplayMode", before);
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "queue.eta_display_mode_set", "clinic", tenantId, was, Map.of("etaDisplayMode", mode, "level", "facility"));
        return new Resolved(mode, "facility");
    }

    /** mode null clears the override (inherit). workingHoursId null = the doctor's own setting, else that session block. */
    @Transactional
    public Resolved put(UUID callerStaffId, UUID doctorId, UUID workingHoursId, String mode) {
        UUID tenantId = tenantContext.current();
        if (mode != null && !PatientEtaView.isMode(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "etaDisplayMode must be one of " + PatientEtaView.MODES + " or null.");
        }
        requireDoctor(tenantId, doctorId);
        String before;
        if (workingHoursId == null) {
            before = repo.doctorMode(tenantId, doctorId).orElse(null);
            repo.upsertDoctorMode(tenantId, doctorId, mode, callerStaffId);
        } else {
            before = repo.sessions(tenantId, doctorId).stream().filter(s -> s.workingHoursId().equals(workingHoursId)).map(s -> java.util.Optional.ofNullable(s.mode())).findFirst().flatMap(o -> o).orElse(null);
            if (repo.setSessionMode(tenantId, doctorId, workingHoursId, mode) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "That working-hours block does not exist for this doctor.");
            }
        }
        var actor = actors.findActor(tenantId, callerStaffId).orElseThrow();
        Map<String, Object> was = new java.util.HashMap<>();
        was.put("etaDisplayMode", before);
        Map<String, Object> now = new java.util.HashMap<>();
        now.put("etaDisplayMode", mode);
        now.put("level", workingHoursId == null ? "doctor" : "session");
        audit.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "queue.eta_display_mode_set", "doctor", doctorId, was, now);
        return resolve(doctorId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always inside the caller's tx
    private void requireDoctor(UUID tenantId, UUID doctorId) {
        if (!queueRepo.staffExists(tenantId, doctorId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "doctor-not-found", "Doctor not found", "That doctor doesn't exist in this clinic.");
        }
    }
}
