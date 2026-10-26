package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-015: queue_entry_events, doctor sessions (explicit and implicit), extend, backfill, tenant isolation. */
class Acc015QueueEventsApiTest extends ApiTestBase {

    @Autowired QueueService queueService;
    @Autowired QueueEventBackfillJob backfill;

    private SeededTenant tenant;
    private SeededStaff doctor;
    private String token;
    private int n = 0;

    private void setUp() {
        tenant = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "d" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        token = loginAndGetAccessToken(doctor);
    }

    private String checkIn() {
        int i = n++;
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Evt" + i, "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0", "dob", (1950 + i) + "-01-01", "gender", "male")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) c.getBody().get("id");
    }

    private ResponseEntity<Map> patch(String id, String status) {
        return exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", status)), Map.class);
    }

    private void walk(String id, String... statuses) {
        for (String s : statuses) assertThat(patch(id, s).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> session(String tok, UUID doc, String action) {
        return exchange("/v1/doctors/" + doc + "/session/" + action, HttpMethod.POST, authedJsonBody(tok, Map.of()), Map.class);
    }

    private List<Map<String, Object>> events(String entryId) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT from_status, to_status, kind, minutes, source, actor_id, occurred_at, doctor_id FROM queue_entry_events WHERE queue_entry_id = ? ORDER BY id",
                UUID.fromString(entryId)));
    }

    private int count(String table) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }

    @Test
    void everyTransitionWritesExactlyOneEvent_andRefusedMovesWriteNone() {
        setUp();
        String id = checkIn();
        assertThat(events(id)).singleElement().satisfies(e -> {
            assertThat(e.get("from_status")).isNull();
            assertThat(e.get("to_status")).isEqualTo("checked_in");
            assertThat(e.get("kind")).isEqualTo("transition");
            assertThat(e.get("source")).isEqualTo("doctor"); // the doctor checked in their own patient
            assertThat(e.get("actor_id")).isEqualTo(doctor.id());
        });
        walk(id, "waiting", "vitals_pending", "vitals_done");
        assertThat(patch(id, "checkout_pending").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // illegal jump
        assertThat(events(id)).hasSize(4);
        assertThat(events(id)).extracting(e -> e.get("to_status")).containsExactly("checked_in", "waiting", "vitals_pending", "vitals_done");
        assertThat(events(id).get(3).get("from_status")).isEqualTo("vitals_pending");
    }

    @Test
    void aRolledBackMoveLeavesNoEventRow() {
        setUp();
        String id = checkIn();
        walk(id, "waiting");
        int before = events(id).size();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            queueService.updateStatus(tenant.id(), doctor.id(), UUID.fromString(id), new com.nabd.hms.queue.dto.QueueStatusUpdateRequest("vitals_pending", "waiting"));
            throw new IllegalStateException("boom after the CAS and the event insert");
        })).hasMessageContaining("boom");
        assertThat(events(id)).hasSize(before);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?", String.class, UUID.fromString(id)))).isEqualTo("waiting");
    }

    @Test
    void transferWritesOneEventPerEntryAndPriorityIsItsOwnKind() {
        setUp();
        String id = checkIn();
        assertThat(exchange("/v1/queue/" + id + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true, "reason", "elderly")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(events(id)).extracting(e -> e.get("kind")).containsExactly("transition", "priority");
        assertThat(events(id).get(1).get("to_status")).isEqualTo("checked_in");
    }

    @Test
    void sessionLifecycle_startPauseResumeEnd_withStateMachine409s() {
        setUp();
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session", HttpMethod.GET, authed(token), Map.class).getBody())
                .containsEntry("state", "out").containsEntry("sessionId", null);
        assertThat(session(token, doctor.id(), "pause").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(session(token, doctor.id(), "resume").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(session(token, doctor.id(), "end").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> started = session(token, doctor.id(), "start");
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(started.getBody()).containsEntry("state", "in");
        assertThat(started.getBody().get("sessionId")).isNotNull();
        assertThat(started.getBody().get("since")).isNotNull();
        ResponseEntity<Map> again = session(token, doctor.id(), "start");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "INVALID_SESSION_STATE");

        ResponseEntity<Map> paused = exchange("/v1/doctors/" + doctor.id() + "/session/pause", HttpMethod.POST,
                authedJsonBody(token, Map.of("reasonCode", "lunch")), Map.class);
        assertThat(paused.getBody()).containsEntry("state", "paused");
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session", HttpMethod.GET, authed(token), Map.class).getBody()).containsEntry("state", "paused");
        assertThat(session(token, doctor.id(), "pause").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT reason_code FROM doctor_session_pauses", String.class))).isEqualTo("lunch");

        assertThat(session(token, doctor.id(), "resume").getBody()).containsEntry("state", "in");
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT resumed_at IS NOT NULL FROM doctor_session_pauses", Boolean.class))).isTrue();
        assertThat(session(token, doctor.id(), "end").getBody()).containsEntry("state", "out");
        assertThat(session(token, doctor.id(), "end").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session/bogus", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // a doctor who ended may start again the same day (a second session row)
        assertThat(session(token, doctor.id(), "start").getBody()).containsEntry("state", "in");
        assertThat(count("doctor_sessions")).isEqualTo(2);

        // end while paused closes the open pause
        session(token, doctor.id(), "pause");
        assertThat(session(token, doctor.id(), "end").getBody()).containsEntry("state", "out");
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM doctor_session_pauses WHERE resumed_at IS NULL", Integer.class))).isZero();

        List<String> acts = inTenantTx(tenant.id(), () -> jdbc.queryForList("SELECT action FROM audit_log WHERE entity_type = 'doctor_session' ORDER BY created_at", String.class));
        assertThat(acts).startsWith("doctor_session.start", "doctor_session.pause", "doctor_session.resume", "doctor_session.end");
        List<String> status = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT envelope->'data'->>'status' FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'scheduling.doctor_status.changed.v1' ORDER BY id", String.class, tenant.id()));
        assertThat(status).startsWith("in", "paused", "in", "out");
    }

    @Test
    void anotherStaffMemberWithoutSetupEditCannotDriveTheDoctorsSession() {
        setUp();
        UUID role = seedRole(tenant.id(), "Desk", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
        SeededStaff desk = seedStaff(tenant, role, "desk" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        String deskToken = loginAndGetAccessToken(desk);
        assertThat(session(deskToken, doctor.id(), "start").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(session(deskToken, desk.id(), "start").getStatusCode()).isEqualTo(HttpStatus.OK); // own session
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session", HttpMethod.GET, authed(deskToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK); // desks may read
        assertThat(session(token, doctor.id(), "start").getStatusCode()).isEqualTo(HttpStatus.OK); // owner role holds setup:edit
        assertThat(session(token, UUID.randomUUID(), "start").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void firstInConsultStartsTheSessionImplicitly_andDoesNotRestartARunningOne() {
        setUp();
        String id = checkIn();
        walk(id, "waiting", "vitals_pending", "vitals_done");
        assertThat(count("doctor_sessions")).isZero();
        walk(id, "in_consult");
        Map<String, Object> s = inTenantTx(tenant.id(), () -> jdbc.queryForMap("SELECT state, started_at FROM doctor_sessions"));
        assertThat(s.get("state")).isEqualTo("in");
        Instant consultAt = ((java.sql.Timestamp) events(id).get(4).get("occurred_at")).toInstant();
        Instant started = ((java.sql.Timestamp) s.get("started_at")).toInstant();
        assertThat(java.time.Duration.between(started, consultAt).abs()).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session", HttpMethod.GET, authed(token), Map.class).getBody()).containsEntry("state", "in");

        // a second patient into consult leaves the one session as is; a paused doctor stays paused
        session(token, doctor.id(), "pause");
        String id2 = checkIn();
        walk(id2, "waiting", "vitals_pending", "vitals_done", "in_consult");
        assertThat(count("doctor_sessions")).isEqualTo(1);
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/session", HttpMethod.GET, authed(token), Map.class).getBody()).containsEntry("state", "paused");
    }

    @Test
    void extendRecordsAnEventOnlyForAConsultInProgress_andOnlyBy5_10_15() {
        setUp();
        String id = checkIn();
        ResponseEntity<Map> early = exchange("/v1/queue/" + id + "/extend", HttpMethod.POST, authedJsonBody(token, Map.of("minutes", 10)), Map.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody()).containsEntry("code", "NOT_IN_CONSULT");
        walk(id, "waiting", "vitals_pending", "vitals_done", "in_consult");
        for (int bad : new int[]{0, 7, 20}) {
            assertThat(exchange("/v1/queue/" + id + "/extend", HttpMethod.POST, authedJsonBody(token, Map.of("minutes", bad)), Map.class).getStatusCode())
                    .as("minutes " + bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<Map> ok = exchange("/v1/queue/" + id + "/extend", HttpMethod.POST, authedJsonBody(token, Map.of("minutes", 10)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("queueEntryId", id).containsEntry("extendedBy", 10);
        assertThat(events(id).get(5)).containsEntry("kind", "extend").containsEntry("to_status", "in_consult");
        assertThat(((Number) events(id).get(5).get("minutes")).intValue()).isEqualTo(10);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_cloudevents WHERE envelope->>'type' = 'visit.queue_entry.changed.v1' AND envelope->'data'->>'extendedMinutes' = '10'", Integer.class))).isEqualTo(1);
    }

    @Test
    void backfillWritesCheckInAndEndForOldVisits_marksThemBackfill_andIsIdempotent() {
        setUp();
        List<String> done = new ArrayList<>();
        for (int i = 0; i < 3; i++) done.add(checkIn());
        String open = checkIn();
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        // make them look like visits from before this feature: no events, 90 days old, three of them completed
        su.update("DELETE FROM queue_entry_events WHERE tenant_id = ?", tenant.id());
        su.update("UPDATE queue_entries SET created_at = now() - interval '90 days', updated_at = now() - interval '90 days' + interval '20 minutes' WHERE tenant_id = ?", tenant.id());
        for (String d : done) su.update("UPDATE queue_entries SET status = 'completed' WHERE id = ?", UUID.fromString(d));

        assertThat(backfill.run(tenant.id())).isEqualTo(3 * 2 + 1);
        for (String d : done) {
            assertThat(events(d)).extracting(e -> e.get("to_status")).containsExactly("checked_in", "completed");
            assertThat(events(d)).allSatisfy(e -> assertThat(e.get("source")).isEqualTo("backfill"));
            assertThat(((java.sql.Timestamp) events(d).get(1).get("occurred_at")).toInstant()).isAfter(((java.sql.Timestamp) events(d).get(0).get("occurred_at")).toInstant());
        }
        assertThat(events(open)).extracting(e -> e.get("to_status")).containsExactly("checked_in");
        assertThat(backfill.run(tenant.id())).isZero();
        assertThat(count("queue_entry_events")).isEqualTo(7);

        // entries that already carry live events are left alone
        String live = checkIn();
        assertThat(backfill.run(tenant.id())).isZero();
        assertThat(events(live)).singleElement().satisfies(e -> assertThat(e.get("source")).isEqualTo("doctor"));
    }

    @Test
    void newTablesAreTenantIsolated() {
        setUp();
        String id = checkIn();
        walk(id, "waiting", "vitals_pending", "vitals_done", "in_consult");
        session(token, doctor.id(), "pause");
        SeededTenant other = seedTenant();
        for (String t : List.of("queue_entry_events", "doctor_sessions", "doctor_session_pauses")) {
            assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class))).as(t).isPositive();
            assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class))).as(t + " seen by another tenant").isZero();
        }
        // and a write for another tenant's row is refused by the policy
        assertThatThrownBy(() -> inTenantTx(other.id(), () -> jdbc.update(
                "INSERT INTO doctor_sessions (tenant_id, doctor_id, clinic_date, state, started_at) VALUES (?,?,current_date,'in',now())", tenant.id(), doctor.id())))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void statusMovePathStaysFast_p95Under200ms() {
        setUp();
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = checkIn();
            long t0 = System.nanoTime();
            patch(id, "waiting");
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        assertThat(ms.get(18)).as("p95 of %s", ms).isLessThan(200);
    }
}
