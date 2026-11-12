package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * S22 QA (t1), COM-043: a confirmation is bound to the patient who owns the appointment and to the clinic of the service token;
 * an Idempotency-Key replay confirms once; the sweep in a clinic east of UTC (Asia/Dubai) asks once for a booking just after
 * local midnight and never for a past or cancelled one.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaCom043ReminderApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;
    @Autowired TenantScope tenantScope;
    @Autowired AppointmentReminders reminders;

    private record Clinic(SeededTenant tenant, UUID doctorId, String staff, String edge, String day) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    private Clinic clinic(String zone) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone, tenant.id());
        clinicClock.evict(tenant.id());
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa22-c43-" + SEQ.incrementAndGet() + "@a.com", "+9194" + String.format("%08d", SEQ.get()), false);
        String staff = loginAndGetAccessToken(doctor);
        LocalDate today = clinicClock.today(tenant.id());
        for (LocalDate d : List.of(today, today.plusDays(1), today.plusDays(2))) {
            exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(staff, Map.of(
                    "dayOfWeek", d.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
        for (Object[] t : new Object[][]{
                {"appt_reminder_24h", "Hello {{1}}, a reminder of your appointment tomorrow, {{2}}, at {{3}}.", List.of("Asha", "12 March", "10:30")},
                {"appt_reminder_2h", "Hello {{1}}, your appointment is today at {{2}}.", List.of("Asha", "10:30")}}) {
            exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(staff, Map.of("name", t[0], "language", "en",
                    "category", "UTILITY", "body", t[1], "examples", t[2])), Map.class);
        }
        return new Clinic(tenant, doctor.id(), staff, InternalTestTokens.mint(encoder, "patient-access-edge", tenant.id()), today.plusDays(2).toString());
    }

    /** A patient with messaging consent; returns {patientId, patientRef}. */
    private String[] patient(Clinic c) throws Exception {
        String phone = "+9193" + String.format("%08d", SEQ.incrementAndGet());
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.staff(), Map.of("name", "Asha " + "RaoKumarSinghPatelReddyIyerNair".substring(SEQ.get() % 20, SEQ.get() % 20 + 6), "phone", phone,
                "dob", (1950 + SEQ.get() % 50) + "-0" + (1 + SEQ.get() % 9) + "-1" + (SEQ.get() % 9), "gender", "female")), Map.class);
        String id = (String) p.getBody().get("id");
        assertThat(id).as("patient create: %s", p.getBody()).isNotNull();
        su().update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?::uuid, 'messaging')", c.tenant().id(), id);
        ResponseEntity<Map> h = exchange(c.base() + "/households/" + sha256(phone), HttpMethod.GET, authed(c.edge()), Map.class);
        String ref = (String) ((List<Map<String, Object>>) h.getBody().get("members")).get(0).get("patientRef");
        return new String[]{id, ref};
    }

    private UUID book(Clinic c, String ref, Instant slot) {
        ResponseEntity<Map> r = exchange(c.base() + "/appointments", HttpMethod.POST, authedJsonBody(c.edge(),
                Map.of("patientRef", ref, "doctorId", c.doctorId().toString(), "startTime", slot.toString())), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("appointmentId"));
    }

    private Instant slot(Clinic c, int hour) {
        ZoneId z = clinicClock.zone(c.tenant().id());
        return LocalDate.parse(c.day()).atTime(hour, 0).atZone(z).toInstant();
    }

    private ResponseEntity<Map> confirm(String edge, String base, UUID id, String ref, String key) {
        return exchange(base + "/appointments/" + id + "/confirm", HttpMethod.POST,
                authedJsonBodyWithHeader(edge, "Idempotency-Key", key, Map.of("patientRef", ref)), Map.class);
    }

    private String state(UUID id) {
        return su().queryForObject("SELECT confirmation_state FROM appointments WHERE id = ?", String.class, id);
    }

    private int confirmedEvents(UUID tenant, UUID id) {
        return su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'scheduling.appointment.confirmed.v1' "
                + "AND payload->>'appointmentId' = ?", Integer.class, tenant, id.toString());
    }

    @Test
    void b01_aConfirmationIsBoundToTheAppointmentsOwnPatient() throws Exception {
        Clinic c = clinic("Asia/Kolkata");
        String[] p1 = patient(c), p2 = patient(c);
        UUID appt2 = book(c, p2[1], slot(c, 11));
        ResponseEntity<Map> r = confirm(c.edge(), c.base(), appt2, p1[1], UUID.randomUUID().toString());
        assertThat(r.getStatusCode().value()).as("P1's ref on P2's appointment -> " + r.getBody()).isIn(403, 404, 409);
        assertThat(state(appt2)).isEqualTo("none");
        assertThat(confirmedEvents(c.tenant().id(), appt2)).isZero();
    }

    @Test
    void b02_anotherClinicsServiceTokenCannotConfirm() throws Exception {
        Clinic a = clinic("Asia/Kolkata"), b = clinic("Asia/Riyadh");
        String[] pb = patient(b);
        UUID appt = book(b, pb[1], slot(b, 12));
        ResponseEntity<Map> r = confirm(a.edge(), b.base(), appt, pb[1], UUID.randomUUID().toString());
        assertThat(r.getStatusCode().value()).as("clinic A's token on clinic B's path -> " + r.getBody()).isIn(401, 403, 404);
        ResponseEntity<Map> r2 = confirm(a.edge(), a.base(), appt, pb[1], UUID.randomUUID().toString());
        assertThat(r2.getStatusCode().value()).as("B's appointment through A's path -> " + r2.getBody()).isIn(403, 404);
        assertThat(state(appt)).isEqualTo("none");
    }

    @Test
    void b03_aReplayedConfirmWithTheSameKeyConfirmsOnce() throws Exception {
        Clinic c = clinic("Asia/Kolkata");
        String[] p = patient(c);
        UUID appt = book(c, p[1], slot(c, 13));
        String key = UUID.randomUUID().toString();
        assertThat(confirm(c.edge(), c.base(), appt, p[1], key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(c.edge(), c.base(), appt, p[1], key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state(appt)).isEqualTo("confirmed");
        assertThat(confirmedEvents(c.tenant().id(), appt)).isEqualTo(1);
        ResponseEntity<Map> second = confirm(c.edge(), c.base(), appt, p[1], UUID.randomUUID().toString());
        System.out.println("QA COM-043 b03: a second tap (new key) -> " + second.getStatusCode() + ", confirmed events now " + confirmedEvents(c.tenant().id(), appt));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmedEvents(c.tenant().id(), appt)).as("round 2 (N-1 fix): a repeated Confirm publishes no second event").isEqualTo(1);
    }

    @Test
    void b04_dubaiClinic_oneDayBeforeReminderForAJustAfterMidnightBooking_noneForPastOrCancelled() throws Exception {
        Clinic c = clinic("Asia/Dubai");
        String[] p = patient(c);
        UUID midnight = book(c, p[1], slot(c, 9));
        UUID past = book(c, p[1], slot(c, 10));
        UUID cancelled = book(c, p[1], slot(c, 11));
        // time travel: midnight starts at 00:30 local tomorrow, 23 h 50 m from now at most; past started 1 h ago; cancelled is in the T-24 window
        ZoneId z = ZoneId.of("Asia/Dubai");
        Instant localMidnightPlus30 = LocalDate.now(z).plusDays(1).atTime(0, 30).atZone(z).toInstant();
        Instant start = Instant.now().plus(Duration.ofHours(23).plusMinutes(50));
        if (localMidnightPlus30.isAfter(Instant.now().plus(Duration.ofHours(18))) && localMidnightPlus30.isBefore(Instant.now().plus(Duration.ofHours(24)))) {
            start = localMidnightPlus30;
        }
        su().update("UPDATE appointments SET start_time = ?, end_time = ?, created_at = now() - interval '2 days' WHERE id = ?",
                Timestamp.from(start), Timestamp.from(start.plusSeconds(900)), midnight);
        su().update("UPDATE appointments SET start_time = now() - interval '1 hour', end_time = now() - interval '45 minutes', created_at = now() - interval '2 days' WHERE id = ?", past);
        su().update("UPDATE appointments SET start_time = now() + interval '22 hours', end_time = now() + interval '22 hours 15 minutes', "
                + "created_at = now() - interval '2 days' WHERE id = ?", cancelled);
        ResponseEntity<Map> cx = exchange(c.base() + "/appointments/" + cancelled + "/cancel", HttpMethod.POST,
                authedJsonBodyWithHeader(c.edge(), "Idempotency-Key", UUID.randomUUID().toString(), Map.of("patientRef", p[1], "reasonCode", "transport")), Map.class);
        assertThat(cx.getStatusCode()).isEqualTo(HttpStatus.OK);

        tenantScope.run(c.tenant().id(), reminders::sweep);
        tenantScope.run(c.tenant().id(), reminders::sweep);
        List<Map<String, Object>> m = su().queryForList("SELECT purpose, not_after FROM notification_requests WHERE subject_type = 'appointment' AND subject_id = ?", midnight);
        Instant s = start;
        assertThat(m).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("purpose", "appt_reminder_24h");
            assertThat(((Timestamp) r.get("not_after")).toInstant()).isCloseTo(s.minus(Duration.ofHours(18)), within(Duration.ofSeconds(1)));
        });
        assertThat(su().queryForObject("SELECT count(*) FROM notification_requests WHERE subject_type = 'appointment' AND subject_id IN (?, ?)", Integer.class, past, cancelled))
                .isZero();
        assertThat(state(past)).as("a past booking is never flagged").isEqualTo("none");
    }
}
