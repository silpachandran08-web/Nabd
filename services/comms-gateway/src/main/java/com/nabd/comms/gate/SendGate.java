package com.nabd.comms.gate;

import com.nabd.comms.store.ConsentSnapshotStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * IDC-006.3 / IDC-008.1: the send-time gate, evaluated when the message is about to leave, never at enqueue time.
 * MARKETING and recall-promo sends need the marketing consent and a subject aged 18+; UTILITY sends need the messaging
 * consent; AUTHENTICATION sends (one-time codes) need neither. A refusal is recorded (send_refusals) and counted
 * (comms_send_refused_total{reason}). The caller (the sender) binds the result to the message: refused = status 'blocked'.
 */
@Component
public class SendGate {

    public enum Category { UTILITY, MARKETING, AUTHENTICATION }

    /**
     * @param promotional    a recall-promo or other promotion sent under a non-MARKETING category still counts as marketing
     * @param subjectDob     date of birth of the patient the message is ABOUT (also when it goes to the guardian's number); never stored. null = no patient subject
     * @param clinicZone     the tenant's time zone, from the send command
     */
    public record Request(UUID tenantId, UUID patientRef, UUID messageId, Category category, boolean promotional, LocalDate subjectDob, ZoneId clinicZone) {
    }

    public record Decision(boolean allowed, String reason) {
        static final Decision ALLOWED = new Decision(true, null);
    }

    private final ConsentSnapshotStore snapshot;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final Clock clock;

    public SendGate(ConsentSnapshotStore snapshot, JdbcTemplate jdbc, MeterRegistry metrics, Clock clock) {
        this.snapshot = snapshot;
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Decision check(Request r) {
        boolean marketing = r.category() == Category.MARKETING || r.promotional();
        String reason = null;
        if (marketing && r.subjectDob() != null && MinorSubject.isMinor(r.subjectDob(), r.clinicZone(), clock)) {
            reason = "minor-subject";
        } else if (marketing && r.patientRef() != null && !snapshot.isGranted(r.tenantId(), r.patientRef(), "marketing")) {
            reason = "no-marketing-consent";
        } else if (!marketing && r.category() == Category.UTILITY && r.patientRef() != null && !snapshot.isGranted(r.tenantId(), r.patientRef(), "messaging")) {
            reason = "no-messaging-consent";
        }
        if (reason == null) {
            return Decision.ALLOWED;
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, r.tenantId().toString());
        jdbc.update("INSERT INTO send_refusals (tenant_id, patient_ref, message_id, category, reason) VALUES (?,?,?,?,?)",
                r.tenantId(), r.patientRef(), r.messageId(), r.category().name(), reason);
        metrics.counter("comms_send_refused_total", "reason", reason).increment();
        return new Decision(false, reason);
    }
}
