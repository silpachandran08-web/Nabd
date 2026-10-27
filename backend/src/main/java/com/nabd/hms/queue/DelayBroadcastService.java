package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.PatientSummaries;
import com.nabd.hms.queue.EtaEngine.Result;
import com.nabd.hms.queue.dto.DelayResponsesResponse;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ACC-024: tells the patients still to be seen that the doctor is late (utility template doctor_delay, time shaped by the display
 * mode) or that the queue is paused (neutral template queue_paused, no reason, no parameters). A patient gets at most one such
 * message per 20 minutes; the others are recorded as 'held'. Runs after the delay or pause committed, one transaction per patient,
 * so a missing template or one bad recipient never undoes the delay and never stops the rest.
 */
@Service
public class DelayBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(DelayBroadcastService.class);
    static final Duration QUIET = Duration.ofMinutes(20);
    static final Duration RESUME_MIN_MOVE = Duration.ofMinutes(10);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final TenantScope tenantScope;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final NotificationPort notifications;
    private final EtaService eta;
    private final EtaDisplayModes modes;
    private final DelayBroadcastRepository repo;
    private final PatientSummaries patients;
    private final ActorDirectory actors;

    DelayBroadcastService(TenantScope tenantScope, TenantContext tenantContext, ClinicClock clock, NotificationPort notifications,
                          EtaService eta, EtaDisplayModes modes, DelayBroadcastRepository repo, PatientSummaries patients, ActorDirectory actors) {
        this.tenantScope = tenantScope;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.notifications = notifications;
        this.eta = eta;
        this.modes = modes;
        this.repo = repo;
        this.patients = patients;
        this.actors = actors;
    }

    /** Who is to be told and what the shaped ETA is for each, read in one transaction. */
    private record Plan(Instant now, ZoneId zone, String mode, String doctorName, Map<UUID, Result> byEntry, Map<UUID, Result> byAppointment,
                        List<DelayBroadcastRepository.Candidate> candidates) {
        Result etaOf(DelayBroadcastRepository.Candidate c) {
            return c.queueEntryId() != null ? byEntry.get(c.queueEntryId()) : byAppointment.get(c.appointmentId());
        }
    }

    private record Outcome(int sent, int held, int noConsent, int failed) {
    }

    /** The delay (or its new minutes) goes to everyone still to be seen before it lapses. @return how many were sent */
    public int onDelay(UUID tenantId, UUID doctorId, UUID delayId, int minutes, Instant expiresAt) {
        Plan plan = tenantScope.run(tenantId, () -> plan(tenantId, doctorId, expiresAt));
        Outcome o = fanOut(tenantId, doctorId, plan, c -> sendDelay(tenantId, doctorId, plan, c, delayId, null, minutes, "delay:" + delayId + ":" + minutes));
        log.info("doctor {} delay {} broadcast: sent={} held={} noConsent={} failed={}", doctorId, delayId, o.sent(), o.held(), o.noConsent(), o.failed());
        return o.sent();
    }

    /** Pause: the neutral message to everyone still to be seen; what each was expected at is kept for the resume. */
    public int onPause(UUID tenantId, UUID doctorId, UUID sessionId) {
        UUID pauseId = tenantScope.run(tenantId, () -> repo.latestPause(tenantId, sessionId)).orElse(null);
        if (pauseId == null) {
            return 0;
        }
        Plan plan = tenantScope.run(tenantId, () -> plan(tenantId, doctorId, ClinicClock.startOf(clock.today(tenantId).plusDays(1), clock.zone(tenantId))));
        Outcome o = fanOut(tenantId, doctorId, plan, c -> sendPause(tenantId, doctorId, plan, c, pauseId));
        log.info("doctor {} pause {} broadcast: sent={} held={} noConsent={} failed={}", doctorId, pauseId, o.sent(), o.held(), o.noConsent(), o.failed());
        return o.sent();
    }

    /** Resume: only patients whose expected time moved by 10 minutes or more (against what the pause message recorded) hear again. */
    public int onResume(UUID tenantId, UUID doctorId, UUID sessionId) {
        UUID pauseId = tenantScope.run(tenantId, () -> repo.latestPause(tenantId, sessionId)).orElse(null);
        if (pauseId == null) {
            return 0;
        }
        Plan plan = tenantScope.run(tenantId, () -> plan(tenantId, doctorId, ClinicClock.startOf(clock.today(tenantId).plusDays(1), clock.zone(tenantId))));
        Outcome o = fanOut(tenantId, doctorId, plan, c -> {
            Result r = plan.etaOf(c);
            Instant before = tenantScope.run(tenantId, () -> repo.expectedAt(tenantId, pauseId, c.patientId())).orElse(null);
            if (r == null || before == null || Duration.between(before, r.p50At()).compareTo(RESUME_MIN_MOVE) < 0) {
                return null; // not told before, or the time barely moved: no message and no row
            }
            int moved = (int) Duration.between(before, r.p50At()).toMinutes();
            return sendDelay(tenantId, doctorId, plan, c, null, pauseId, moved, "resume:" + pauseId);
        });
        log.info("doctor {} resume of pause {} broadcast: sent={} held={} noConsent={} failed={}", doctorId, pauseId, o.sent(), o.held(), o.noConsent(), o.failed());
        return o.sent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Plan plan(UUID tenantId, UUID doctorId, Instant until) {
        LocalDate day = clock.today(tenantId);
        ZoneId zone = clock.zone(tenantId);
        Instant now = clock.now();
        eta.recompute(doctorId, day); // the delay or pause just committed: do not read a projection that predates it
        Map<UUID, Result> byEntry = new HashMap<>();
        Map<UUID, Result> byAppt = new HashMap<>();
        for (Result r : eta.day(doctorId, day).results()) {
            if (r.entryId() != null) byEntry.put(r.entryId(), r);
            if (r.appointmentId() != null) byAppt.put(r.appointmentId(), r);
        }
        String doctor = actors.findActor(tenantId, doctorId).map(ActorDirectory.Actor::name).orElse("The doctor");
        return new Plan(now, zone, modes.resolve(doctorId).mode(), doctor, byEntry, byAppt,
                repo.candidates(tenantId, doctorId, day, ClinicClock.startOf(day, zone), until));
    }

    private interface Send {
        /** @return the row status written, or null when nothing was to be sent */
        String send(DelayBroadcastRepository.Candidate c);
    }

    private Outcome fanOut(UUID tenantId, UUID doctorId, Plan plan, Send send) {
        int sent = 0, held = 0, noConsent = 0, failed = 0;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (DelayBroadcastRepository.Candidate c : plan.candidates()) {
            if (!seen.add(c.patientId())) {
                continue; // one message per patient however many bookings
            }
            String status;
            try {
                status = send.send(c);
            } catch (RuntimeException e) {
                status = "failed";
                log.warn("delay broadcast to a patient of doctor {} failed: {}", doctorId, e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
            if (status == null) continue;
            switch (status) {
                case "queued" -> sent++;
                case "held" -> held++;
                case "skipped_no_consent" -> noConsent++;
                default -> failed++;
            }
        }
        return new Outcome(sent, held, noConsent, failed);
    }

    private String sendDelay(UUID tenantId, UUID doctorId, Plan plan, DelayBroadcastRepository.Candidate c, UUID delayId, UUID pauseId, int minutes, String key) {
        Result r = plan.etaOf(c);
        String when = timeText(plan.mode(), r, minutes, plan.zone());
        return deliver(tenantId, doctorId, plan, c, delayId, pauseId, NotificationPurpose.DOCTOR_DELAY, key,
                p -> List.of(firstName(p), plan.doctorName(), when), r == null ? null : r.p50At());
    }

    private String sendPause(UUID tenantId, UUID doctorId, Plan plan, DelayBroadcastRepository.Candidate c, UUID pauseId) {
        Result r = plan.etaOf(c);
        return deliver(tenantId, doctorId, plan, c, null, pauseId, NotificationPurpose.QUEUE_PAUSED, "pause:" + pauseId,
                p -> List.of(), r == null ? null : r.p50At());
    }

    private String deliver(UUID tenantId, UUID doctorId, Plan plan, DelayBroadcastRepository.Candidate c, UUID delayId, UUID pauseId,
                           NotificationPurpose purpose, String key, java.util.function.Function<UUID, List<String>> params, Instant expected) {
        String status;
        try {
            status = tenantScope.run(tenantId, () -> {
                if (repo.messagedSince(tenantId, c.patientId(), plan.now().minus(QUIET))) {
                    repo.insert(tenantId, doctorId, delayId, pauseId, c, "held", null, expected, plan.now());
                    return "held";
                }
                var outcome = notifications.request(new NotificationRequest(purpose, "appointment", c.appointmentId() != null ? c.appointmentId() : c.queueEntryId(),
                        c.patientId(), null, null, params.apply(c.patientId()), null, null, null, key + ":" + c.patientId()));
                String s = "blocked".equals(outcome.status()) ? "skipped_no_consent" : "queued";
                repo.insert(tenantId, doctorId, delayId, pauseId, c, s, outcome.requestId(), expected, plan.now());
                return s;
            });
        } catch (RuntimeException e) { // the request (e.g. no approved template) rolled that one transaction back: keep a row saying so
            tenantScope.run(tenantId, () -> repo.insert(tenantId, doctorId, delayId, pauseId, c, "failed", null, expected, plan.now()));
            throw e;
        }
        return status;
    }

    private String firstName(UUID patientId) {
        String name = patients.find(patientId).map(PatientSummaries.Summary::name).orElse("");
        String first = name.isBlank() ? "" : name.trim().split("\\s+")[0];
        return first.isBlank() ? "there" : first;
    }

    /** What the patient is told as the new time, within what their display mode allows ("11:00", "11:00-11:15", or the delay only). */
    static String timeText(String mode, Result r, int minutes, ZoneId zone) {
        var view = PatientEtaView.shape(mode, new PatientEtaView.Input(r == null ? null : r.p50At(), r == null ? null : r.p80At(),
                r == null ? null : r.position(), minutes), zone);
        if (view.exactAt() != null) return HH_MM.format(view.exactAt().atZone(zone));
        if (view.from() != null) return HH_MM.format(view.from().atZone(zone)) + "-" + HH_MM.format(view.to().atZone(zone));
        return "about " + minutes + " min later than planned";
    }

    /** The desk's response list for one delay. */
    @Transactional
    public DelayResponsesResponse responses(UUID doctorId, UUID delayId) {
        UUID tenantId = tenantContext.current();
        if (!repo.delayOf(tenantId, doctorId, delayId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "delay-not-found", "Delay not found", "No such delay for this doctor.");
        }
        Map<String, Integer> counts = new TreeMap<>();
        List<DelayResponsesResponse.Item> items = repo.forDelay(tenantId, delayId).stream().map(r -> {
            counts.merge(r.status(), 1, Integer::sum);
            if (r.response() != null) counts.merge("responded", 1, Integer::sum);
            String name = patients.find(r.patientId()).map(PatientSummaries.Summary::name).orElse("");
            return new DelayResponsesResponse.Item(r.appointmentId(), name, r.status(), r.response(),
                    "delivered".equals(r.status()) ? r.createdAt() : null, r.respondedAt());
        }).toList();
        return new DelayResponsesResponse(items, counts);
    }
}
