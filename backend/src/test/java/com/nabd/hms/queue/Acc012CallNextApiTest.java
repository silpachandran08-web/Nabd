package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-012: call-next by policy, position and why-here, late arrivals, reserved walk-in slots, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc012CallNextApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(9000);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token, LocalDate today) {
        int dow(LocalDate d) {
            return d.getDayOfWeek().getValue() % 7;
        }
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "acc12" + n + "@a.com", "+9195" + (4000000 + n), false);
        return new Ctx(tenant, doctor, loginAndGetAccessToken(doctor), clinicClock.today(tenant.id()));
    }

    private String patient(Ctx c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+91995" + (4000000 + n),
                "dob", "1985-05-05", "gender", "female")), Map.class);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> hours(Ctx c, LocalDate day, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("dayOfWeek", c.dow(day), "startTime", "00:00:00", "endTime", "23:59:00", "slotMinutes", 15));
        body.putAll(extra);
        return exchange("/v1/doctors/" + c.doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token, body), Map.class);
    }

    private ResponseEntity<Map> checkIn(Ctx c, String patientId, String appointmentId) {
        Map<String, Object> body = new HashMap<>(Map.of("patientId", patientId, "doctorId", c.doctor.id().toString()));
        if (appointmentId != null) body.put("appointmentId", appointmentId);
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, body), Map.class);
    }

    private void toWaiting(Ctx c, String entryId) {
        assertThat(exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(c.token,
                Map.of("status", "waiting", "expectedStatus", "checked_in")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> callNext(Ctx c, UUID expectedHead) {
        return exchange("/v1/queue/doctors/" + c.doctor.id() + "/call-next", HttpMethod.POST,
                authedJsonBody(c.token, expectedHead == null ? Map.of() : Map.of("expectedHeadId", expectedHead.toString())), Map.class);
    }

    /** A booked, checked-in and waiting patient; slot = minutes from the top of the next hour (future, today). */
    private String bookedWaiting(Ctx c, int slotMinutesAfterBase) {
        Instant base = Instant.now().plusSeconds(3600).atZone(ZONE).withMinute(0).withSecond(0).withNano(0).toInstant();
        String pid = patient(c);
        ResponseEntity<Map> a = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.id().toString(), "patientId", pid, "startTime", base.plusSeconds(60L * slotMinutesAfterBase).toString())), Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) checkIn(c, pid, (String) a.getBody().get("id")).getBody().get("id");
        toWaiting(c, id);
        return id;
    }

    private String walkInWaiting(Ctx c) {
        String id = (String) checkIn(c, patient(c), null).getBody().get("id");
        toWaiting(c, id);
        return id;
    }

    @Test
    void callNextFollowsThePolicyAndReportsTheReason() {
        Ctx c = ctx();
        Assumptions.assumeTrue(LocalDateTime.now(ZONE).getHour() < 21, "needs future slots today");
        hours(c, c.today, Map.of());
        String w1 = walkInWaiting(c);          // arrived first
        String b1 = bookedWaiting(c, 0);
        String b2 = bookedWaiting(c, 15);
        // booked patients first (1:3), the walk-in after them although it arrived first
        ResponseEntity<Map> r1 = callNext(c, null);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) r1.getBody().get("entry")).get("id")).isEqualTo(b1);
        assertThat(r1.getBody().get("reason")).isEqualTo("booked_on_time");
        assertThat(((Map) r1.getBody().get("entry")).get("status")).isNotEqualTo("waiting");
        assertThat(((Map) callNext(c, UUID.fromString(b2)).getBody().get("entry")).get("id")).isEqualTo(b2);
        ResponseEntity<Map> r3 = callNext(c, null);
        assertThat(((Map) r3.getBody().get("entry")).get("id")).isEqualTo(w1);
        assertThat(r3.getBody().get("reason")).isEqualTo("interleave");
        // nobody left
        ResponseEntity<Map> empty = callNext(c, null);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(empty.getBody().get("code")).isEqualTo("QUEUE_EMPTY");
        List<String> acts = inTenantTx(c.tenant.id(), () -> jdbc.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'queue_entry' AND action = 'queue.call_next'", String.class));
        assertThat(acts).hasSize(3);
    }

    @Test
    void staleHeadPausedQueueAndTheInterleaveCounterAcrossCalls() {
        Ctx c = ctx();
        Assumptions.assumeTrue(LocalDateTime.now(ZONE).getHour() < 20, "needs future slots today");
        hours(c, c.today, Map.of("walkInRatio", 2));
        String w1 = walkInWaiting(c);
        String b1 = bookedWaiting(c, 0), b2 = bookedWaiting(c, 15), b3 = bookedWaiting(c, 30);
        ResponseEntity<Map> stale = callNext(c, UUID.fromString(w1));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code")).isEqualTo("STALE_HEAD");

        exchange("/v1/doctors/" + c.doctor.id() + "/session/start", HttpMethod.POST, authedJsonBody(c.token, Map.of()), Map.class);
        exchange("/v1/doctors/" + c.doctor.id() + "/session/pause", HttpMethod.POST, authedJsonBody(c.token, Map.of()), Map.class);
        ResponseEntity<Map> paused = callNext(c, null);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paused.getBody().get("code")).isEqualTo("QUEUE_PAUSED");
        exchange("/v1/doctors/" + c.doctor.id() + "/session/resume", HttpMethod.POST, authedJsonBody(c.token, Map.of()), Map.class);

        // 1:2 -> B1, B2, W1, B3 (the counter is read from earlier calls, it does not restart at each tap)
        List<Object> called = new ArrayList<>();
        for (int i = 0; i < 4; i++) called.add(((Map) callNext(c, null).getBody().get("entry")).get("id"));
        assertThat(called).containsExactly(b1, b2, w1, b3);
    }

    @Test
    void priorityFlagIsCalledFirstAndTheOverrideIsAudited() {
        Ctx c = ctx();
        Assumptions.assumeTrue(LocalDateTime.now(ZONE).getHour() < 21, "needs future slots today");
        hours(c, c.today, Map.of());
        String b1 = bookedWaiting(c, 0);
        String w1 = walkInWaiting(c);
        assertThat(exchange("/v1/queue/" + w1 + "/reorder", HttpMethod.POST, authedJsonBody(c.token,
                Map.of("priority", true, "reason", "chest pain")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = callNext(c, null);
        assertThat(((Map) r.getBody().get("entry")).get("id")).isEqualTo(w1);
        assertThat(r.getBody().get("reason")).isEqualTo("priority");
        List<String> details = inTenantTx(c.tenant.id(), () -> jdbc.queryForList(
                "SELECT after::text FROM audit_log WHERE action = 'queue.call_next' AND entity_id = ?", String.class, UUID.fromString(w1)));
        assertThat(details).hasSize(1);
        assertThat(details.get(0)).contains("priority").doesNotContain("chest pain");
        assertThat(((Map) callNext(c, null).getBody().get("entry")).get("id")).isEqualTo(b1);
    }

    @Test
    void lateBookedPatientIsPlacedAsWalkInWithTheReasonShown() {
        Ctx c = ctx();
        Assumptions.assumeTrue(LocalDateTime.now(ZONE).getHour() >= 1, "needs a slot 25 minutes ago, same clinic day");
        hours(c, c.today, Map.of("lateGraceMinutes", 15));
        String pid = patient(c);
        UUID appt = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(25 * 60);
        inTenantTx(c.tenant.id(), () -> jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?,?)",
                appt, c.tenant.id(), UUID.fromString(pid), c.doctor.id(), Timestamp.from(start), Timestamp.from(start.plusSeconds(900))));
        ResponseEntity<Map> in = checkIn(c, pid, appt.toString());
        assertThat(in.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(in.getBody().get("orderReason")).isEqualTo("late arrival - placed as walk-in");
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT placed_as_walkin_late FROM queue_entries WHERE id = ?",
                Boolean.class, UUID.fromString((String) in.getBody().get("id"))))).isTrue();
    }

    @Test
    void checkInAnswersWithPositionAndRangeAndTheDoctorListCarriesThemToo() {
        Ctx c = ctx();
        hours(c, c.today, Map.of());
        long t0 = System.nanoTime();
        ResponseEntity<Map> in = checkIn(c, patient(c), null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(in.getBody().get("position")).isEqualTo(1);
        assertThat((String) in.getBody().get("expectedRange")).matches("\\d\\d:\\d\\d-\\d\\d:\\d\\d");
        assertThat(ms).as("check-in round trip incl. placement").isLessThan(1000);
        ResponseEntity<Map> second = checkIn(c, patient(c), null);
        assertThat(second.getBody().get("position")).isEqualTo(2);
        List<Map> list = exchange("/v1/queue?doctorId=" + c.doctor.id(), HttpMethod.GET, authed(c.token), List.class).getBody();
        assertThat(list).extracting(m -> m.get("position")).containsExactlyInAnyOrder(1, 2);
        assertThat(list.get(0).get("orderReason")).isIn("interleave", "booked_on_time", "priority");
    }

    @Test
    void reservedWalkInSlotIsNeverOfferedAndRefusedToBookings() {
        Ctx c = ctx();
        LocalDate d = c.today.plusDays(2);
        ResponseEntity<Map> created = hours(c, d, Map.of("startTime", "09:00:00", "endTime", "13:00:00", "reservedWalkInTimes", List.of("11:00:00")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("reservedWalkInTimes")).isEqualTo(List.of("11:00:00"));
        Instant eleven = d.atTime(11, 0).atZone(ZONE).toInstant();
        List<String> slots = exchange("/v1/doctors/" + c.doctor.id() + "/availability?date=" + d, HttpMethod.GET, authed(c.token), List.class).getBody();
        assertThat(slots).contains(d.atTime(10, 45).atZone(ZONE).toInstant().toString(), d.atTime(11, 15).atZone(ZONE).toInstant().toString())
                .doesNotContain(eleven.toString());
        Map session = (Map) ((List) exchange("/v1/doctors/" + c.doctor.id() + "/availability/sessions?date=" + d, HttpMethod.GET, authed(c.token), Map.class)
                .getBody().get("sessions")).get(0);
        assertThat((List<String>) session.get("slots")).doesNotContain(eleven.toString());
        ResponseEntity<Map> refused = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "doctorId", c.doctor.id().toString(), "patientId", patient(c), "startTime", eleven.toString())), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code")).isEqualTo("RESERVED_FOR_WALKIN");
        // the neighbouring slot still books
        assertThat(exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token, Map.of("doctorId", c.doctor.id().toString(),
                "patientId", patient(c), "startTime", d.atTime(11, 15).atZone(ZONE).toInstant().toString())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // not offered to the Patient Access Edge either
        String edge = InternalTestTokens.mint(encoder, "patient-access-edge", c.tenant.id());
        Map av = exchange("/internal/v1/tenants/" + c.tenant.id() + "/availability?doctorId=" + c.doctor.id() + "&from=" + d + "&to=" + d,
                HttpMethod.GET, authed(edge), Map.class).getBody();
        List<Map> sessions = (List<Map>) ((Map) ((List) av.get("doctors")).get(0)).get("sessions");
        assertThat((List<String>) sessions.get(0).get("slots")).isNotEmpty().doesNotContain(eleven.toString());
        // reserved times must sit on the grid
        assertThat(hours(c, d.plusDays(1), Map.of("startTime", "09:00:00", "endTime", "13:00:00", "reservedWalkInTimes", List.of("11:05:00")))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reservedSlotsOfOneClinicAreInvisibleToAnother() {
        Ctx a = ctx(), b = ctx();
        UUID hoursId = UUID.fromString((String) hours(a, a.today.plusDays(2), Map.of("startTime", "09:00:00", "endTime", "13:00:00",
                "reservedWalkInTimes", List.of("11:00:00"))).getBody().get("id"));
        assertThat(inTenantTx(a.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM session_reserved_slots WHERE working_hours_id = ?",
                Integer.class, hoursId))).isEqualTo(1);
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM session_reserved_slots WHERE working_hours_id = ?",
                Integer.class, hoursId))).isZero();
    }
}
