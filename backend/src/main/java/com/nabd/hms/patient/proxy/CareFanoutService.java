package com.nabd.hms.patient.proxy;

import com.nabd.hms.patient.api.CareRecipients;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * IDC-014: dual delivery. A closed visit with an issued visit summary sends "your visit instructions are ready" to every CLINICAL
 * recipient (the patient and record-scope caregivers); a no-show of a member aged 60+ sends a missed-visit alert to the LOGISTICS
 * caregivers (not the patient). Each request is marked subject_type care_&lt;class&gt; with subject_id = the patient, so
 * NotificationService re-checks the recipient at send time (a caregiver revoked or downgraded meanwhile gets nothing). Bodies
 * carry the patient's first name only. Idempotent: one dedupe key per event and recipient.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class CareFanoutService {

    private static final Logger log = LoggerFactory.getLogger(CareFanoutService.class);

    private final JdbcTemplate jdbc;
    private final CareRecipients care;
    private final NotificationPort notifications;

    CareFanoutService(JdbcTemplate jdbc, CareRecipients care, NotificationPort notifications) {
        this.jdbc = jdbc;
        this.care = care;
        this.notifications = notifications;
    }

    /** visit.completed.v1 (entryId): only when the doctor issued a visit summary for this visit. */
    void visitCompleted(UUID entryId) {
        UUID patient = jdbc.queryForList("SELECT patient_id FROM queue_entries WHERE id = ?", UUID.class, entryId).stream().findFirst().orElse(null);
        if (patient == null || jdbc.queryForList("SELECT 1 FROM clinical_documents WHERE queue_entry_id = ? AND doc_type = 'visit_summary' AND status = 'issued' LIMIT 1", entryId).isEmpty()) {
            return;
        }
        send(NotificationPurpose.VISIT_INSTRUCTIONS, CareRecipients.MessageClass.CLINICAL, patient, care.recipients(patient, CareRecipients.MessageClass.CLINICAL),
                "visit-instructions:" + entryId);
    }

    /** scheduling.appointment.no_show.v1 (appointmentId): caregivers of an elderly member only. */
    void noShow(UUID appointmentId) {
        UUID patient = jdbc.queryForList("SELECT patient_id FROM appointments WHERE id = ?", UUID.class, appointmentId).stream().findFirst().orElse(null);
        if (patient == null || !care.elderly(patient)) {
            return;
        }
        send(NotificationPurpose.MISSED_VISIT_ALERT, CareRecipients.MessageClass.LOGISTICS, patient,
                care.recipients(patient, CareRecipients.MessageClass.LOGISTICS).stream().filter(r -> !"self".equals(r.relation())).toList(),
                "missed-visit:" + appointmentId);
    }

    private void send(NotificationPurpose purpose, CareRecipients.MessageClass c, UUID patient, List<CareRecipients.Recipient> to, String key) {
        if (to.isEmpty()) {
            return;
        }
        if (!notifications.sendable(purpose, "en")) { // no approved template in this clinic: nothing to send, retrying will not help
            log.info("care fan-out {} skipped: no approved template", purpose.code());
            return;
        }
        String firstName = jdbc.queryForObject("SELECT split_part(name, ' ', 1) FROM patients WHERE id = ?", String.class, patient);
        for (CareRecipients.Recipient r : to) {
            notifications.request(new NotificationRequest(purpose, "care_" + c.wire(), patient, r.patientId(), null, null, List.of(firstName),
                    null, null, null, key + ":" + r.patientId()));
        }
    }
}
