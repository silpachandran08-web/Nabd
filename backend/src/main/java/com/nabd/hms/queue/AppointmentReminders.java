package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.ClinicPolicies;
import com.nabd.hms.shared.notify.NotificationPurpose;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-043: the reminder cascade, run by the minute sweep (DeskExceptionSweepJob) for one tenant (bound by the caller).
 * <ol>
 *   <li>Reminders at clinic_policies.reminder_hours_before (default "24,2"): an hour mark of 6 or more is the day-before reminder
 *   (window 6 h), smaller is the T-2 h one (window 45 min). Requested once per appointment and kind while inside its window; a
 *   cancelled booking is never swept (and cancelBySubject voids what was already requested); a reschedule is a new row with its
 *   own reminders.</li>
 *   <li>Nudge: the day-before reminder went out, no confirmation by the clinic cutoff (confirmation_cutoff_hours before the
 *   start, default 12) and more than 2 h left: one more message, state 'nudged'.</li>
 *   <li>Flag: still unconfirmed 2 h before the start: state 'unconfirmed_flagged' and the desk exceptions inbox item (ACC-032).</li>
 * </ol>
 * Times are instants compared with ClinicClock.now(); the rendered date and time are the clinic's local ones.
 */
@Service
public class AppointmentReminders {

    static final Duration DAY_WINDOW = Duration.ofMinutes(360);
    static final Duration SHORT_WINDOW = Duration.ofMinutes(45);
    static final Duration FLAG_BEFORE = Duration.ofHours(2);
    static final int DEFAULT_CUTOFF_HOURS = 12; // shortcut: product default until the clinic sets confirmation_cutoff_hours (decision pending)
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final ClinicPolicies policies;
    private final AppointmentChangeNotifier notifier;
    private final DeskExceptions desk;

    AppointmentReminders(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, ClinicPolicies policies,
                         AppointmentChangeNotifier notifier, DeskExceptions desk) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.policies = policies;
        this.notifier = notifier;
        this.desk = desk;
    }

    private record Due(UUID id, UUID patientId, UUID doctorId, Instant start) {
    }

    /** @return reminders and nudges requested plus appointments flagged */
    @Transactional
    public int sweep() {
        UUID t = tenantContext.current();
        Instant now = clock.now();
        int n = 0;
        List<String> dayKinds = new ArrayList<>();
        for (int h : reminderHours(t)) {
            boolean day = h >= 6;
            Duration window = day ? DAY_WINDOW : SHORT_WINDOW;
            NotificationPurpose purpose = day ? NotificationPurpose.APPT_REMINDER_24H : NotificationPurpose.APPT_REMINDER_2H;
            String kind = "t" + h + "h";
            if (day) {
                dayKinds.add(kind);
            }
            for (Due d : due(t, now, Duration.ofHours(h), window, kind)) {
                Instant windowEnd = d.start().minus(Duration.ofHours(h)).plus(window);
                n += request(t, purpose, d, kind, windowEnd.isBefore(d.start()) ? windowEnd : d.start());
            }
        }
        n += nudge(t, now, dayKinds);
        n += flag(t, now);
        return n;
    }

    private int request(UUID t, NotificationPurpose purpose, Due d, String kind, Instant notAfter) {
        UUID requestId = notifier.reminder(t, purpose, d.id(), d.patientId(), d.start(), notAfter,
                "appt-" + kind + ":" + d.id()); // dedupe per appointment and kind
        if (requestId == null) {
            return 0; // no approved template yet: asked again on the next sweep while the window is open
        }
        jdbc.update("INSERT INTO appointment_reminders (tenant_id, appointment_id, kind, request_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                t, d.id(), kind, requestId);
        return 1;
    }

    private List<Due> due(UUID t, Instant now, Duration before, Duration window, String kind) {
        Timestamp at = Timestamp.from(now);
        return jdbc.query("""
                SELECT a.id, a.patient_id, a.doctor_id, a.start_time FROM appointments a
                 WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.start_time > ?
                   AND a.start_time - ?::interval <= ? AND a.start_time - ?::interval + ?::interval > ?
                   AND NOT EXISTS (SELECT 1 FROM appointment_reminders r WHERE r.tenant_id = a.tenant_id AND r.appointment_id = a.id AND r.kind = ?)
                 ORDER BY a.start_time LIMIT ?
                """, (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("doctor_id", UUID.class), rs.getTimestamp("start_time").toInstant()),
                t, at, interval(before), at, interval(before), interval(window), at, kind, BATCH);
    }

    /** Cutoff passed, the day-before reminder was sent, nobody confirmed, more than 2 h left: one nudge before the flag time. */
    private int nudge(UUID t, Instant now, List<String> dayKinds) {
        if (dayKinds.isEmpty()) {
            return 0;
        }
        int cutoff = cutoffHours(t);
        Timestamp at = Timestamp.from(now);
        List<Due> rows = jdbc.query("""
                SELECT a.id, a.patient_id, a.doctor_id, a.start_time FROM appointments a
                 WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.confirmation_state = 'none'
                   AND a.patient_confirmed_at IS NULL AND a.confirmed_at IS NULL
                   AND a.start_time - make_interval(hours => ?) <= ? AND a.start_time - interval '2 hours' > ?
                   AND EXISTS (SELECT 1 FROM appointment_reminders r WHERE r.tenant_id = a.tenant_id AND r.appointment_id = a.id
                                AND r.kind = ANY (?::text[]))
                 ORDER BY a.start_time LIMIT ?
                """, (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("doctor_id", UUID.class), rs.getTimestamp("start_time").toInstant()), t, cutoff, at, at, dayKinds.toArray(new String[0]), BATCH);
        int n = 0;
        for (Due d : rows) {
            if (request(t, NotificationPurpose.APPT_NUDGE, d, "nudge", d.start().minus(FLAG_BEFORE)) == 1) {
                jdbc.update("UPDATE appointments SET confirmation_state = 'nudged' WHERE tenant_id = ? AND id = ? AND confirmation_state = 'none'", t, d.id());
                n++;
            }
        }
        return n;
    }

    /** Two hours to go and still no answer: flagged, and the desk sees it in the exceptions inbox. */
    private int flag(UUID t, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<Due> rows = jdbc.query("""
                UPDATE appointments a SET confirmation_state = 'unconfirmed_flagged'
                 WHERE a.tenant_id = ? AND a.status = 'scheduled' AND a.confirmation_state IN ('none', 'nudged')
                   AND a.patient_confirmed_at IS NULL AND a.confirmed_at IS NULL
                   AND a.start_time > ? AND a.start_time - interval '2 hours' <= ?
                   AND a.created_at <= a.start_time - interval '2 hours'
                RETURNING a.id, a.patient_id, a.doctor_id, a.start_time
                """, (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("doctor_id", UUID.class), rs.getTimestamp("start_time").toInstant()), t, at, at);
        for (Due d : rows) {
            desk.open(t, DeskExceptions.UNCONFIRMED, "warn", "appointment", d.id(), Map.of("doctorId", d.doctorId(), "startTime", d.start()));
        }
        return rows.size();
    }

    private List<Integer> reminderHours(UUID t) {
        String v = policies.policyValue(t, "reminder_hours_before").orElse("24,2");
        List<Integer> out = new ArrayList<>();
        for (String part : v.split(",")) {
            try {
                int h = Integer.parseInt(part.trim());
                if (h >= 1 && h <= 168 && !out.contains(h)) {
                    out.add(h);
                }
            } catch (NumberFormatException ignored) {
                // a malformed entry is skipped; the others still apply
            }
        }
        return out.isEmpty() ? List.of(24, 2) : out;
    }

    private int cutoffHours(UUID t) {
        try {
            int h = Integer.parseInt(policies.policyValue(t, "confirmation_cutoff_hours").orElse(String.valueOf(DEFAULT_CUTOFF_HOURS)).trim());
            return h >= 3 && h <= 72 ? h : DEFAULT_CUTOFF_HOURS;
        } catch (NumberFormatException e) {
            return DEFAULT_CUTOFF_HOURS;
        }
    }

    private static String interval(Duration d) {
        return d.toMinutes() + " minutes";
    }
}
