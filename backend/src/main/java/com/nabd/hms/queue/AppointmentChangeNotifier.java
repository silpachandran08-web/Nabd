package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ACC-006: tells the patient when staff move or cancel a booking. Params are first name, date and time only (no diagnosis, no
 * visit reason). A clinic without an approved template must still be able to move a booking, so the request runs in a savepoint
 * and a 422 TEMPLATE_NOT_SENDABLE is logged, not raised. Called inside the caller's transaction (tenant bound).
 */
@Component
class AppointmentChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(AppointmentChangeNotifier.class);

    private final NotificationPort notifications;
    private final JdbcTemplate jdbc;
    private final ClinicClock clock;
    private final TransactionTemplate nested;

    AppointmentChangeNotifier(NotificationPort notifications, JdbcTemplate jdbc, ClinicClock clock, PlatformTransactionManager txm) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    void moved(UUID tenantId, UUID appointmentId, UUID patientId, Instant newStart) {
        send(tenantId, NotificationPurpose.APPT_RESCHEDULED, appointmentId, patientId, newStart, "moved-" + appointmentId + "-" + newStart.toEpochMilli());
    }

    void cancelled(UUID tenantId, UUID appointmentId, UUID patientId, Instant oldStart) {
        send(tenantId, NotificationPurpose.APPT_CANCELLED_BY_CLINIC, appointmentId, patientId, oldStart, "cancelled-" + appointmentId);
    }

    private void send(UUID tenantId, NotificationPurpose purpose, UUID appointmentId, UUID patientId, Instant at, String dedupe) {
        ZoneId zone = clock.zone(tenantId);
        String name = jdbc.queryForObject("SELECT name FROM patients WHERE tenant_id = ? AND id = ?", String.class, tenantId, patientId);
        String first = name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
        List<String> params = List.of(first, DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).format(at.atZone(zone)),
                DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(at.atZone(zone)));
        try {
            nested.executeWithoutResult(s -> notifications.request(new NotificationRequest(purpose, "appointment", appointmentId, patientId, null, null,
                    params, null, null, null, dedupe)));
        } catch (ApiException e) { // TEMPLATE_NOT_SENDABLE
            log.warn("{} notice for appointment {} not sent (no approved template)", purpose, appointmentId); // shortcut: no retry queue, the desk can still offer alternatives
        }
    }
}
