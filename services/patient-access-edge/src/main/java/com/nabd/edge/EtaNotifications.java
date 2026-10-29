package com.nabd.edge;

import com.nabd.edge.NotifyRules.Action;
import com.nabd.edge.NotifyRules.Track;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ACC-044: keeps the per-visit state of {@link NotifyRules} and sends the messages. A visit is tracked once the patient confirmed it
 * in the bot (ReminderIntent) or the booking flow calls {@link #track}. ETA deltas arrive from the stream; a 30 s sweep re-evaluates the
 * clock-driven rule (leave-now fires on time even when no delta comes). The state moves before the message goes out, so a
 * message is never sent twice (a failed send is counted, not retried). Row locks make concurrent replicas safe.
 * shortcut: the text goes out through the Comms Gateway send API as plain text; the utility templates leave_now / eta_shift in a
 * send window (COM-020/023) replace it when those land.
 */
@Component
public class EtaNotifications {

    private static final Logger log = LoggerFactory.getLogger(EtaNotifications.class);

    private final TenantTx tx;
    private final CommsClient comms;
    private final Clock clock;
    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;
    private final CoreClient core;

    EtaNotifications(TenantTx tx, CommsClient comms, Clock clock, MeterRegistry meters, JdbcTemplate jdbc, CoreClient core) {
        this.tx = tx;
        this.comms = comms;
        this.clock = clock;
        this.meters = meters;
        this.jdbc = jdbc;
        this.core = core;
    }

    public void track(UUID tenantId, String appointmentRef, String contactHash, String patientRef) {
        tx.run(tenantId, (j, s) -> j.update("""
                insert into visit_notification_state (tenant_id, appointment_ref, contact_hash, patient_ref) values (?, ?, ?, ?)
                on conflict (tenant_id, appointment_ref) do update set contact_hash = excluded.contact_hash, patient_ref = excluded.patient_ref""",
                tenantId, appointmentRef, contactHash, patientRef));
    }

    public void untrack(UUID tenantId, String appointmentRef) {
        tx.run(tenantId, (j, s) -> j.update("delete from visit_notification_state where tenant_id = ? and appointment_ref = ?", tenantId, appointmentRef));
    }

    /** An ETA delta (visit.eta.updated.v1). Unknown visits are ignored: the patient never opted in. */
    public void onEta(UUID tenantId, String appointmentRef, Instant p50, String mode, boolean arrived) {
        onEtaNoEvaluate(tenantId, appointmentRef, p50, mode, arrived);
        evaluate(tenantId, appointmentRef);
    }

    private void onEtaNoEvaluate(UUID tenantId, String appointmentRef, Instant p50, String mode, boolean arrived) {
        tx.run(tenantId, (j, s) -> j.update("""
                update visit_notification_state set current_p50 = ?, display_mode = ?, arrived = arrived or ?, updated_at = now()
                where tenant_id = ? and appointment_ref = ?""", p50 == null ? null : Timestamp.from(p50), mode, arrived, tenantId, appointmentRef));
    }

    public void markArrived(UUID tenantId, String appointmentRef) {
        tx.run(tenantId, (j, s) -> j.update("update visit_notification_state set arrived = true, updated_at = now() where tenant_id = ? and appointment_ref = ?",
                tenantId, appointmentRef));
    }

    @Scheduled(fixedDelay = 30_000)
    // OPS-013: no @SchedulerLock on purpose: per replica is safe, evaluate() locks the row and the state moves before the send
    void sweep() {
        try {
            record Due(UUID tenantId, String ref) {
            }
            List<Due> due = jdbc.query("select tenant_id, appointment_ref from edge_due_visits()",
                    (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2)));
            for (Due d : due) {
                try {
                    refresh(d.tenantId(), d.ref());
                    evaluate(d.tenantId(), d.ref());
                } catch (RuntimeException e) {
                    meters.counter("edge_eta_notify_errors_total").increment();
                }
            }
        } catch (RuntimeException e) {
            meters.counter("edge_eta_notify_errors_total").increment();
        }
    }

    /**
     * shortcut: until Core produces visit.eta.updated.v1 (no producer yet) the sweep pulls the current p50, mode and arrival from Core's
     * status read (one call per tracked visit per 30 s, served from the ETA projection); drop this when the event flows.
     */
    private void refresh(UUID tenantId, String ref) {
        String contact = tx.run(tenantId, (j, s) -> j.query("select contact_hash from visit_notification_state where tenant_id = ? and appointment_ref = ?",
                (rs, i) -> rs.getString(1), tenantId, ref).stream().findFirst().orElse(null));
        if (contact == null) {
            return;
        }
        try {
            for (CoreClient.VisitStatus v : core.status(tenantId, contact)) {
                if (v.appointmentId().toString().equals(ref)) {
                    onEtaNoEvaluate(tenantId, ref, v.p50(), v.mode(), "arrived".equals(v.status()));
                }
            }
        } catch (RuntimeException e) {
            meters.counter("edge_eta_refresh_failed_total").increment(); // keep the last known p50
        }
    }

    void evaluate(UUID tenantId, String ref) {
        Instant now = clock.instant();
        record Send(String contact, Action action, long minutes) {
        }
        Send send = tx.run(tenantId, (j, s) -> {
            var rows = j.query("""
                    select contact_hash, patient_ref, current_p50, display_mode, arrived, last_notified_p50, messages_sent, leave_now_sent_at,
                           last_shift_at, pending_shift_since from visit_notification_state where tenant_id = ? and appointment_ref = ? for update""",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getString(2),
                            new Track(inst(rs.getTimestamp(3)), rs.getString(4), rs.getBoolean(5), inst(rs.getTimestamp(6)), rs.getInt(7),
                                    inst(rs.getTimestamp(8)), inst(rs.getTimestamp(9)), inst(rs.getTimestamp(10)))},
                    tenantId, ref);
            if (rows.isEmpty()) {
                return null;
            }
            Track t = (Track) rows.getFirst()[2];
            Integer travel = j.query("select minutes from travel_buffers where tenant_id = ? and patient_ref = ?", (rs, i) -> rs.getInt(1),
                    tenantId, rows.getFirst()[1]).stream().findFirst().orElse(null);
            NotifyRules.Result r = NotifyRules.decide(t, travel, now);
            Track n = r.next();
            j.update("""
                    update visit_notification_state set last_notified_p50 = ?, messages_sent = ?, leave_now_sent_at = ?, last_shift_at = ?,
                           pending_shift_since = ?, updated_at = now() where tenant_id = ? and appointment_ref = ?""",
                    ts(n.lastNotified()), n.sent(), ts(n.leaveNowSentAt()), ts(n.lastShiftAt()), ts(n.pendingSince()), tenantId, ref);
            return r.action() == Action.NONE ? null
                    : new Send((String) rows.getFirst()[0], r.action(), Math.max(0, Duration.between(now, n.p50()).toMinutes()));
        });
        if (send == null) {
            return;
        }
        String text = send.action() == Action.LEAVE_NOW
                ? "It is time to leave for the clinic. You should get there about 5 minutes before your turn."
                : "Your expected time has changed. It is now about " + send.minutes() + " minutes from now.";
        try {
            comms.send(tenantId, "whatsapp", send.contact(), text);
            meters.counter("edge_eta_notify_total", "kind", send.action().name().toLowerCase()).increment();
        } catch (RuntimeException e) {
            meters.counter("edge_eta_notify_send_failed_total").increment();
            log.warn("eta message not sent for visit {}", ref);
        }
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
