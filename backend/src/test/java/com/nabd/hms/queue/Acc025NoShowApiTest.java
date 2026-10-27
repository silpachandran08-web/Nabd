package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-025: the grace-period job, the manual no-show (QA-31), undo within the clinic day, the late-arrival path and the event.
 * The clock is frozen on Tuesday 2026-03-10 in Asia/Kolkata, so the 09:15 to 09:17 window never flakes. The ETA pull-forward is in Acc025NoShowPullForwardTest.
 */
@Import(ClinicDayCheckInClockApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc025NoShowApiTest extends ApiTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    @Autowired MutableClock clock;
    @Autowired NoShowAutoMarkJob job;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private static String policy(boolean autoMark, int grace) {
        String vt = "{\"acceptance\":\"auto\",\"dailyCapPerDoctor\":200,\"reviewerRole\":\"reception\"}";
        return "{\"preset\":null,\"solo\":false,\"visitTypes\":{\"new\":" + vt + ",\"followUp\":" + vt + ",\"procedure\":" + vt + ",\"teleconsult\":" + vt + "},"
                + "\"checkIn\":{\"modes\":[\"desk\"]},\"eta\":{\"displayMode\":\"range\",\"leaveHomeMarginMinutes\":0},\"walkIn\":{\"interleave\":\"none\"},"
                + "\"noShow\":{\"graceMinutes\":" + grace + ",\"autoMark\":" + autoMark + ",\"pullForward\":true,\"depositAfterRepeat\":false},"
                + "\"workflow\":{\"vitalsEnabled\":true,\"triageEnabled\":false}}";
    }

    private Clinic clinic(boolean autoMark, int grace) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "ns" + n + "@a.com", "+9195" + (3000000 + n), false);
        String token = loginAndGetAccessToken(doctor); // real time first: the JWT must be valid for the decoder
        inTenantTx(tenant.id(), () -> jdbc.update("INSERT INTO booking_policy_versions (tenant_id, version, status, config, published_at) VALUES (?,1,'published',?::jsonb,now())",
                tenant.id(), policy(autoMark, grace)));
        return new Clinic(tenant, doctor, token);
    }

    private void at(String time) {
        clock.setLocal(DAY + "T" + time, ZONE.getId());
    }

    private String patient(Clinic c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+91996" + (2000000 + n),
                "dob", "1980-01-15", "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    /** Written as superuser: bookings in the past, any status, any day. */
    private UUID appt(Clinic c, LocalDate day, String time, String status, String decisionSource) {
        UUID id = UUID.randomUUID();
        Instant start = day.atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toInstant();
        try (Connection su = superuserConnection();
             PreparedStatement ps = su.prepareStatement("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, decision_source, arrived_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, c.tenant.id());
            ps.setObject(3, UUID.fromString(patient(c)));
            ps.setObject(4, c.doctor.id());
            ps.setTimestamp(5, Timestamp.from(start));
            ps.setTimestamp(6, Timestamp.from(start.plusSeconds(900)));
            ps.setString(7, status);
            ps.setString(8, decisionSource);
            ps.setTimestamp(9, "arrived".equals(status) ? Timestamp.from(start) : null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private Map<String, Object> row(UUID id) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement("SELECT status, decision_source, no_show_at, no_show_marked_by, no_show_reason_code, late_arrival_queue_entry_id FROM appointments WHERE id = ?")) {
            ps.setObject(1, id);
            var rs = ps.executeQuery();
            rs.next();
            Map<String, Object> m = new java.util.HashMap<>();
            for (String k : List.of("status", "decision_source", "no_show_at", "no_show_marked_by", "no_show_reason_code", "late_arrival_queue_entry_id")) {
                m.put(k, rs.getObject(k));
            }
            return m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sql(String query, Object... args) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> post(Clinic c, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(c.token, body), Map.class);
    }

    private static String code(ResponseEntity<Map> r) {
        return (String) r.getBody().get("code");
    }

    // ---- the job ----

    @Test
    void ac1_aNineOClockBookingIsMarkedBetween0915And0917WithDecisionSourceSystem() {
        Clinic c = clinic(true, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", "auto");
        UUID later = appt(c, DAY, "09:30", "scheduled", "auto");
        UUID yesterday = appt(c, DAY.minusDays(1), "09:00", "scheduled", "auto");

        at("09:14:59");
        job.runOnce();
        assertThat(row(a).get("status")).as("grace not over yet").isEqualTo("scheduled");

        at("09:16:00");
        job.runOnce();
        Map<String, Object> r = row(a);
        assertThat(r.get("status")).isEqualTo("no_show");
        assertThat(r.get("decision_source")).isEqualTo("system");
        assertThat(r.get("no_show_marked_by")).as("null = the system").isNull();
        Instant at = ((Timestamp) r.get("no_show_at")).toInstant();
        assertThat(at).isBetween(DAY.atTime(9, 15).atZone(ZONE).toInstant(), DAY.atTime(9, 17).atZone(ZONE).toInstant());
        assertThat(row(later).get("status")).as("09:30 is not due").isEqualTo("scheduled");
        assertThat(row(yesterday).get("status")).as("an earlier day is left to staff").isEqualTo("scheduled");

        job.runOnce();
        job.runOnce();
        List<String> events = inTenantTx(c.tenant.id(), () -> jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'scheduling.appointment.no_show.v1'", String.class));
        assertThat(events).as("one event, however often the job runs").hasSize(1);
        assertThat(events.get(0)).contains(a.toString()).contains("\"markedBy\": \"system\"").doesNotContain("Zq");
    }

    @Test
    void theGraceComesFromThePolicy() {
        Clinic c = clinic(true, 20);
        UUID a = appt(c, DAY, "09:00", "scheduled", null);
        at("09:19:00");
        job.runOnce();
        assertThat(row(a).get("status")).isEqualTo("scheduled");
        at("09:21:00");
        job.runOnce();
        assertThat(row(a).get("status")).isEqualTo("no_show");
    }

    @Test
    void autoMarkOffMeansTheJobLeavesEverythingAlone() {
        Clinic c = clinic(false, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", null);
        Clinic never = clinic(true, 15);
        sql("DELETE FROM booking_policy_versions WHERE tenant_id = ?", never.tenant.id()); // never published: built-in default, auto-mark off
        UUID b = appt(never, DAY, "09:00", "scheduled", null);
        at("11:00:00");
        job.runOnce();
        assertThat(row(a).get("status")).isEqualTo("scheduled");
        assertThat(row(b).get("status")).isEqualTo("scheduled");
    }

    @Test
    void ac2_anArrivedOrCheckedInAppointmentIsNeverMarked() {
        Clinic c = clinic(true, 15);
        UUID arrived = appt(c, DAY, "09:00", "arrived", null);
        UUID scheduledWithQueueEntry = appt(c, DAY, "09:30", "scheduled", null);
        sql("INSERT INTO departments (tenant_id, name) VALUES (?, 'NoShowDept' || substr(gen_random_uuid()::text, 1, 6))", c.tenant.id());
        sql("INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number, status, source) "
                + "SELECT gen_random_uuid(), a.tenant_id, a.id, a.patient_id, a.doctor_id, "
                + "(SELECT min(d.id::text)::uuid FROM departments d WHERE d.tenant_id = a.tenant_id), ?::date, 1, 'checked_in', 'walk_in' FROM appointments a WHERE a.id = ?", DAY.toString(), scheduledWithQueueEntry);
        at("12:00:00");
        job.runOnce();
        assertThat(row(arrived).get("status")).isEqualTo("arrived");
        assertThat(row(scheduledWithQueueEntry).get("status")).as("a queue entry means checked in").isEqualTo("scheduled");
    }

    // ---- manual (QA-31) ----

    @Test
    void ac3_staffMarkANeverArrivedAppointmentAndGet200_QA31() {
        Clinic c = clinic(false, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", "auto");
        at("09:30:00");
        ResponseEntity<Map> r = post(c, "/v1/appointments/" + a + "/no-show", Map.of("reasonCode", "illness"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "no_show").containsEntry("decisionSource", "auto").containsEntry("noShowMarkedBy", c.doctor.id().toString());
        assertThat(row(a).get("no_show_reason_code")).isEqualTo("illness");
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'appointment.no_show_marked' AND entity_id = ?", Integer.class, a))).isEqualTo(1);
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE event_type = 'scheduling.appointment.no_show.v1'", String.class)).get(0))
                .contains("\"markedBy\": \"staff\"");

        // a repeat of the same action is harmless; the body is optional
        assertThat(post(c, "/v1/appointments/" + a + "/no-show", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID b = appt(c, DAY, "09:15", "scheduled", null);
        ResponseEntity<Map> noBody = exchange("/v1/appointments/" + b + "/no-show", HttpMethod.POST, authed(c.token), Map.class);
        assertThat(noBody.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void manualMarkRefusals() {
        Clinic c = clinic(false, 15);
        at("09:30:00");
        UUID arrived = appt(c, DAY, "09:00", "arrived", null);
        ResponseEntity<Map> a = post(c, "/v1/appointments/" + arrived + "/no-show", Map.of());
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(a)).isEqualTo("APPOINTMENT_ARRIVED");

        UUID future = appt(c, DAY, "11:00", "scheduled", null);
        ResponseEntity<Map> early = post(c, "/v1/appointments/" + future + "/no-show", Map.of());
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(early)).isEqualTo("NOT_YET_DUE");
        at("11:00:00");
        assertThat(post(c, "/v1/appointments/" + future + "/no-show", Map.of()).getStatusCode()).as("boundary: due at the start time").isEqualTo(HttpStatus.OK);

        UUID due = appt(c, DAY, "08:00", "scheduled", null);
        ResponseEntity<Map> badCode = post(c, "/v1/appointments/" + due + "/no-show", Map.of("reasonCode", "not_a_reason"));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(badCode)).isEqualTo("REASON_INVALID");
        assertThat(row(due).get("status")).isEqualTo("scheduled");

        UUID cancelled = appt(c, DAY, "07:00", "cancelled", null);
        assertThat(post(c, "/v1/appointments/" + cancelled + "/no-show", Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(c, "/v1/appointments/" + UUID.randomUUID() + "/no-show", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- undo ----

    @Test
    void ac4_undoTheSameDayRestoresScheduledAndTheOriginalDecisionSource_nextDayIs409() {
        Clinic c = clinic(true, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", "auto");
        at("09:16:00");
        job.runOnce();
        assertThat(row(a)).containsEntry("status", "no_show").containsEntry("decision_source", "system");

        at("17:00:00");
        ResponseEntity<Map> undo = post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of());
        assertThat(undo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undo.getBody()).containsEntry("status", "scheduled").containsEntry("decisionSource", "auto");
        assertThat(row(a).get("no_show_at")).isNull();
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'appointment.no_show_undone' AND entity_id = ?", Integer.class, a))).isEqualTo(1);

        ResponseEntity<Map> notNoShow = post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of());
        assertThat(notNoShow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(notNoShow)).isEqualTo("NOT_NO_SHOW");

        // marked again, then the next clinic day: 23:59 still works, 00:00 of the next day does not
        job.runOnce();
        assertThat(row(a).get("status")).isEqualTo("no_show");
        clock.setLocal(DAY + "T23:59:59", ZONE.getId());
        assertThat(post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of()).getStatusCode()).as("boundary: last second of the day").isEqualTo(HttpStatus.OK);
        job.runOnce();
        sql("UPDATE appointments SET status = 'no_show', no_show_at = ? WHERE id = ?", Timestamp.from(DAY.atTime(9, 16).atZone(ZONE).toInstant()), a);
        clock.setLocal(DAY.plusDays(1) + "T00:00:00", ZONE.getId());
        ResponseEntity<Map> late = post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of());
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(late)).isEqualTo("UNDO_WINDOW_PASSED");
        assertThat(row(a).get("status")).isEqualTo("no_show");
    }

    @Test
    void undoIsRefusedWhenTheSlotWasTakenMeanwhile() {
        Clinic c = clinic(false, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", null);
        at("09:30:00");
        assertThat(post(c, "/v1/appointments/" + a + "/no-show", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        appt(c, DAY, "09:00", "scheduled", null); // the freed slot is booked again
        ResponseEntity<Map> undo = post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of());
        assertThat(undo.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(row(a).get("status")).isEqualTo("no_show");
    }

    // ---- late arrival ----

    @Test
    void ac6_aLateArrivalIssuesAWalkInTokenLinkedToTheOriginalAppointment() {
        Clinic c = clinic(true, 15);
        UUID a = appt(c, DAY, "09:00", "scheduled", null);
        at("09:16:00");
        job.runOnce();
        at("09:40:00");

        ResponseEntity<Map> r = post(c, "/v1/queue/late-arrival", Map.of("appointmentId", a.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("source", "walk_in").containsEntry("doctorId", c.doctor.id().toString());
        assertThat(r.getBody().get("tokenNumber")).isNotNull();
        UUID entry = UUID.fromString((String) r.getBody().get("id"));
        assertThat(row(a)).containsEntry("status", "no_show").containsEntry("late_arrival_queue_entry_id", entry);
        Map<String, Object> q = inTenantTx(c.tenant.id(), () -> jdbc.queryForMap("SELECT appointment_id, placed_as_walkin_late FROM queue_entries WHERE id = ?", entry));
        assertThat(q.get("appointment_id")).as("a walk-in token, not the booking's own").isNull();
        assertThat(q.get("placed_as_walkin_late")).isEqualTo(true);

        ResponseEntity<Map> again = post(c, "/v1/queue/late-arrival", Map.of("appointmentId", a.toString()));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("ALREADY_CHECKED_IN");
        ResponseEntity<Map> undo = post(c, "/v1/appointments/" + a + "/no-show/undo", Map.of());
        assertThat(undo.getStatusCode()).as("the patient is already in the queue").isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(undo)).isEqualTo("LATE_ARRIVAL_LINKED");
    }

    @Test
    void lateArrivalNeedsANoShow() {
        Clinic c = clinic(false, 15);
        UUID scheduled = appt(c, DAY, "09:00", "scheduled", null);
        at("09:30:00");
        ResponseEntity<Map> r = post(c, "/v1/queue/late-arrival", Map.of("appointmentId", scheduled.toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo("NOT_NO_SHOW");
        assertThat(post(c, "/v1/queue/late-arrival", Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(c, "/v1/queue/late-arrival", Map.of("appointmentId", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- permissions and tenants ----

    @Test
    void permissionsAndTenantIsolation() {
        Clinic a = clinic(false, 15);
        Clinic b = clinic(false, 15);
        UUID mine = appt(a, DAY, "09:00", "scheduled", null);
        UUID viewOnly = seedRole(a.tenant.id(), "Viewer " + UUID.randomUUID().toString().substring(0, 6), false,
                new com.nabd.hms.common.ModuleGrant("queue", true, false, false, false, false, false, false));
        int n = SEQ.incrementAndGet();
        Clinic viewer = new Clinic(a.tenant, a.doctor, loginAndGetAccessToken(seedStaff(a.tenant, viewOnly, "v" + n + "@a.com", "+9195" + (3500000 + n), false)));
        at("09:30:00");
        for (String path : List.of("/v1/appointments/" + mine + "/no-show", "/v1/appointments/" + mine + "/no-show/undo")) {
            assertThat(post(b, path, Map.of()).getStatusCode()).as("other clinic: %s", path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(post(b, "/v1/queue/late-arrival", Map.of("appointmentId", mine.toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(row(mine).get("status")).isEqualTo("scheduled");

        assertThat(post(viewer, "/v1/appointments/" + mine + "/no-show", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(viewer, "/v1/appointments/" + mine + "/no-show/undo", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(viewer, "/v1/queue/late-arrival", Map.of("appointmentId", mine.toString())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
