package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-033: the patient's own check-in, called by the Patient Access Edge (service identity, tenant in the path). It reuses
 * QueueService.checkIn through CallOrderService, so the arrived flip, date check, token and double-submit lock are exactly the
 * desk's. A refused attempt (outside the geofence, no appointment today) is recorded and opens an item in the desk exceptions
 * inbox in a transaction that commits; the error is thrown after that commit. The shared location is used for one distance and
 * is never stored or logged: checkin_attempts keeps distance_m only.
 */
@Service
class SelfCheckInService {

    static final int ACCURACY_CAP_M = 100; // a poor fix can excuse at most this much distance, so a huge accuracy cannot pass a far patient
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Schema(name = "SelfCheckInCandidate")
    public record Candidate(String patientRef, String firstName, UUID appointmentId, boolean checkedIn) {
    }

    @Schema(name = "SelfCheckInContext")
    public record Context(String facilityCode, boolean geofenceRequired, int radiusM, boolean walkinAllowed, List<Candidate> candidates) {
    }

    @Schema(name = "SelfCheckInResponse")
    public record Result(UUID queueEntryId, int token, Integer position, String expectedRange) {
    }

    @Schema(name = "FacilityCheckInConfig")
    public record Config(String checkinCode, BigDecimal lat, BigDecimal lng, boolean geofenceRequired, int geofenceRadiusM,
                         boolean walkinAllowed, String whatsappNumber) {
    }

    /** What the transaction decided: a result, or an error to throw once the attempt and the exception item have committed. */
    private record Outcome(Result result, ApiException failure) {
    }

    private final TenantScope tenantScope;
    private final SelfCheckInRepository repo;
    private final AppointmentRepository appointmentRepo;
    private final QueueRepository queueRepo;
    private final CallOrderService callOrder;
    private final PatientRefs patientRefs;
    private final DeskExceptions desk;
    private final AuditService audit;
    private final com.nabd.hms.iam.api.ActorDirectory actors;
    private final ClinicClock clock;

    SelfCheckInService(TenantScope tenantScope, SelfCheckInRepository repo, AppointmentRepository appointmentRepo, QueueRepository queueRepo,
                       CallOrderService callOrder, PatientRefs patientRefs, DeskExceptions desk, AuditService audit, com.nabd.hms.iam.api.ActorDirectory actors, ClinicClock clock) {
        this.tenantScope = tenantScope;
        this.repo = repo;
        this.appointmentRepo = appointmentRepo;
        this.queueRepo = queueRepo;
        this.callOrder = callOrder;
        this.patientRefs = patientRefs;
        this.desk = desk;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    /** What the Edge needs to start a check-in: the facility code, the policy, and which household members have an appointment today. */
    Context context(UUID tid, String phoneHash) {
        return tenantScope.run(tid, () -> {
            var s = repo.settings(tid);
            List<Candidate> out = new ArrayList<>();
            for (var m : phoneHash == null ? List.<PatientRefs.HouseholdMember>of() : patientRefs.household(phoneHash)) {
                UUID patientId = patientRefs.patientIdFor(m.patientRef()).orElse(null);
                if (patientId == null) continue;
                for (var a : today(tid, patientId)) out.add(new Candidate(m.patientRef(), m.firstName(), a.id(), "arrived".equals(a.status())));
            }
            return new Context(s.map(SelfCheckInRepository.Settings::code).orElse(null), s.map(SelfCheckInRepository.Settings::geofenceRequired).orElse(false),
                    s.map(SelfCheckInRepository.Settings::radiusM).orElse(200), s.map(SelfCheckInRepository.Settings::walkinAllowed).orElse(false), out);
        });
    }

    Result checkIn(UUID tid, ServiceIdentity edge, UUID appointmentId, UUID walkInDoctorId, String patientRef, String channel, Double lat, Double lng, Double accuracyM) {
        if (appointmentId != null && walkInDoctorId != null) throw invalid("Send an appointmentId or a walkIn, not both.");
        if (lat != null && (lat < -90 || lat > 90) || lng != null && (lng < -180 || lng > 180) || (lat == null) != (lng == null)) {
            throw invalid("location needs a valid lat and lng.");
        }
        Outcome o = tenantScope.run(tid, () -> attempt(tid, appointmentId, walkInDoctorId, patientRef, channel, lat, lng, accuracyM));
        if (o.failure() != null) throw o.failure();
        return o.result();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tenant tx
    private Outcome attempt(UUID tid, UUID appointmentId, UUID walkInDoctorId, String patientRef, String channel, Double lat, Double lng, Double accuracyM) {
        UUID patientId = patientRefs.patientIdFor(patientRef).orElseThrow(SelfCheckInService::notOwner);
        var settings = repo.settings(tid);
        UUID attemptId = UUID.randomUUID();
        QueueModels.AppointmentRow appt = null;
        if (appointmentId != null) {
            appt = appointmentRepo.findById(tid, appointmentId).orElseThrow(SelfCheckInService::notOwner);
            if (!appt.patientId().equals(patientId)) throw notOwner();
        } else if (walkInDoctorId == null) {
            var mine = today(tid, patientId);
            if (!mine.isEmpty()) appt = appointmentRepo.findById(tid, mine.get(0).id()).orElse(null);
        } else if (settings.isEmpty() || !settings.get().walkinAllowed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "walkin-not-allowed", "Walk-in check-in is off", "This clinic does not take walk-ins by QR.",
                    Map.of("code", "WALKIN_NOT_ALLOWED"));
        }

        if (appt != null && "arrived".equals(appt.status())) { // the desk (or an earlier tap) got there first: hand back the same token
            Map<String, Object> have = new HashMap<>(queueRepo.findCheckInOf(tid, appt.id()).orElse(Map.of()));
            have.put("code", "ALREADY_CHECKED_IN");
            have.put("token", have.get("tokenNumber"));
            repo.recordAttempt(tid, attemptId, appt.id(), channel, null, "duplicate");
            return new Outcome(null, new ApiException(HttpStatus.CONFLICT, "already-checked-in", "Already checked in", "This appointment has already been checked in.", have));
        }
        LocalDate today = clock.today(tid);
        boolean liveToday = appt != null && "scheduled".equals(appt.status()) && appt.startTime().atZone(clock.zone(tid)).toLocalDate().equals(today);
        if (appt == null && walkInDoctorId == null || appt != null && !liveToday) {
            String ref = appt == null ? attemptId.toString() : appt.id().toString();
            return refuse(tid, attemptId, appt == null ? null : appt.id(), channel, null, "no_appointment", appt == null ? "no_appointment" : "not_today", ref, appt != null,
                    new ApiException(HttpStatus.CONFLICT, "appointment-not-today", "No appointment today", "There is no appointment for this patient today; the front desk will help.",
                            Map.of("code", "APPOINTMENT_NOT_TODAY")));
        }

        Integer distance = null;
        if (settings.isPresent() && settings.get().geofenceRequired()) {
            if (lat == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "location-required", "Location needed", "Share your location to check in.", Map.of("code", "LOCATION_REQUIRED"));
            }
            var s = settings.get();
            if (s.lat() == null || s.lng() == null) { // required but never placed on the map: the desk decides
                return refuse(tid, attemptId, appt == null ? null : appt.id(), channel, null, "outside_geofence", "geofence_not_set", ref(appt, attemptId), appt != null, outside());
            }
            double d = haversineM(lat, lng, s.lat().doubleValue(), s.lng().doubleValue());
            distance = (int) Math.round(d);
            if (Math.max(0, d - Math.min(accuracyM == null ? 0 : Math.max(0, accuracyM), ACCURACY_CAP_M)) > s.radiusM()) {
                return refuse(tid, attemptId, appt == null ? null : appt.id(), channel, distance, "outside_geofence", "outside_geofence", ref(appt, attemptId), appt != null, outside());
            }
        }

        CheckInRequest req = appt != null ? new CheckInRequest(appt.id(), appt.patientId(), appt.doctorId(), "online", null, null)
                : new CheckInRequest(null, patientId, walkInDoctorId, "online", null, null);
        QueueEntryResponse e = callOrder.checkInAnnotated(tid, null, req); // null staff = the patient via the Edge (QueueService audits it)
        repo.recordAttempt(tid, attemptId, appt == null ? null : appt.id(), channel, distance, "checked_in");
        return new Outcome(new Result(e.id(), e.tokenNumber(), e.position(), e.expectedRange()), null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tenant tx
    private Outcome refuse(UUID tid, UUID attemptId, UUID appointmentId, String channel, Integer distance, String outcome, String reason, String refId,
                           boolean refIsAppointment, ApiException failure) {
        repo.recordAttempt(tid, attemptId, appointmentId, channel, distance, outcome);
        Map<String, Object> params = new HashMap<>();
        params.put("reason", reason);
        params.put("channel", channel);
        if (distance != null) params.put("distanceM", distance);
        if (appointmentId != null) params.put("appointmentId", appointmentId);
        desk.open(tid, DeskExceptions.CHECKIN_FAILED, "warn", refIsAppointment ? "appointment" : "checkin_attempt", UUID.fromString(refId), params);
        return new Outcome(null, failure);
    }

    private static String ref(QueueModels.AppointmentRow appt, UUID attemptId) {
        return (appt == null ? attemptId : appt.id()).toString();
    }

    private static ApiException outside() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "outside-geofence", "Too far from the clinic",
                "You are not close enough to check in yourself; the front desk has been told.", Map.of("code", "OUTSIDE_GEOFENCE"));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // marker: always called inside the caller's tenant tx
    private List<SelfCheckInRepository.TodayAppointment> today(UUID tid, UUID patientId) {
        LocalDate day = clock.today(tid);
        var zone = clock.zone(tid);
        return repo.appointmentsBetween(tid, patientId, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_000.0 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // ---- staff side: the clinic's check-in settings and the door poster ----

    Config config(UUID tid) {
        return tenantScope.run(tid, () -> repo.settings(tid).map(s -> new Config(s.code(), s.lat(), s.lng(), s.geofenceRequired(), s.radiusM(), s.walkinAllowed(), s.waNumber()))
                .orElse(new Config(null, null, null, false, 200, false, null)));
    }

    Config saveConfig(UUID tid, UUID staffId, Config c) {
        if (c.geofenceRadiusM() < 20 || c.geofenceRadiusM() > 5000) throw invalid("geofenceRadiusM must be from 20 to 5000.");
        if ((c.lat() == null) != (c.lng() == null)) throw invalid("Send both lat and lng, or neither.");
        if (c.lat() != null && (c.lat().abs().compareTo(BigDecimal.valueOf(90)) > 0 || c.lng().abs().compareTo(BigDecimal.valueOf(180)) > 0)) throw invalid("lat or lng out of range.");
        if (c.geofenceRequired() && c.lat() == null) throw invalid("Set the clinic's location before requiring the geofence.");
        if (c.whatsappNumber() != null && !c.whatsappNumber().matches("[0-9]{8,15}")) throw invalid("whatsappNumber must be 8 to 15 digits, no plus sign.");
        return tenantScope.run(tid, () -> {
            String code = repo.settings(tid).map(SelfCheckInRepository.Settings::code).orElseGet(SelfCheckInService::newCode);
            repo.upsert(tid, code, c.lat(), c.lng(), c.geofenceRequired(), c.geofenceRadiusM(), c.walkinAllowed(), c.whatsappNumber());
            var actor = actors.findActor(tid, staffId).orElseThrow();
            audit.record(tid, "staff", staffId, actor.name(), actor.role(), null, "facility.checkin_config.updated", "facility_checkin_settings", tid, null,
                    Map.of("geofenceRequired", c.geofenceRequired(), "walkinAllowed", c.walkinAllowed(), "radiusM", c.geofenceRadiusM()));
            return config(tid);
        });
    }

    /** The door QR's text: a wa.me deep link with the prefilled "CHECKIN <code>" the bot recognises. */
    String posterLink(UUID tid) {
        Config c = config(tid);
        if (c.checkinCode() == null || c.whatsappNumber() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "checkin-not-configured", "Check-in is not set up", "Save the clinic's WhatsApp number in the check-in settings first.",
                    Map.of("code", "CHECKIN_NOT_CONFIGURED"));
        }
        return "https://wa.me/" + c.whatsappNumber() + "?text=CHECKIN%20" + c.checkinCode();
    }

    List<SelfCheckInRepository.Attempt> recent(UUID tid, int limit) {
        return tenantScope.run(tid, () -> repo.recent(tid, Math.max(1, Math.min(limit, 100))));
    }

    private static String newCode() { // 8 characters, no 0/O/1/I; a clash is a unique-key error and the staff retry
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return b.toString();
    }

    private static ApiException notOwner() {
        return new ApiException(HttpStatus.FORBIDDEN, "not-owner", "Not the patient's appointment", "That appointment does not belong to this patient.", Map.of("code", "NOT_OWNER"));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
