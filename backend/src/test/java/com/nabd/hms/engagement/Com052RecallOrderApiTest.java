package com.nabd.hms.engagement;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-052: 'Review in 2 weeks' at consult close becomes a tracked recall; booking the follow-up marks it booked. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Com052RecallOrderApiTest extends ApiTestBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 400_000);

    private record Clinic(SeededTenant t, SeededStaff doctor, String doc, String otherDoc, String desk, UUID doctorRole) {
    }

    private record Visit(String patientId, String entry) {
    }

    private static String phone() {
        return "+9193" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'Asia/Kolkata' WHERE id = ?", t.id());
        ModuleGrant clinicalEdit = new ModuleGrant("clinical", true, true, true, false, false, false, false);
        ModuleGrant queueView = new ModuleGrant("queue", true, false, false, false, false, false, false);
        UUID doctorRole = seedRole(t.id(), "RcDoctor", false, clinicalEdit, queueView);
        UUID deskRole = seedRole(t.id(), "RcDesk", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(t, doctorRole, "rc" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String other = loginAndGetAccessToken(seedStaff(t, doctorRole, "rc" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String desk = loginAndGetAccessToken(seedStaff(t, deskRole, "rc" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String admin = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "rc" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        int dow = followUpDay().getDayOfWeek().getValue() % 7;
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(admin, Map.of(
                "dayOfWeek", dow, "startTime", "09:00:00", "endTime", "12:00:00", "slotMinutes", 30)), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        return new Clinic(t, doctor, loginAndGetAccessToken(doctor), other, desk, doctorRole);
    }

    private static LocalDate followUpDay() {
        return LocalDate.now(IST).plusDays(3);
    }

    private Visit visit(Clinic c) {
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.desk(), Map.of("name", "Rc Patient " +
                Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(), "dob", LocalDate.of(1971, 3, 3).plusDays(SEQ.get() % 9000).toString(),
                "gender", "female")), Map.class).getBody().get("id");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.desk(), Map.of(
                "patientId", patientId, "doctorId", c.doctor().id().toString())), Map.class);
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Visit(patientId, (String) q.getBody().get("id"));
    }

    private ResponseEntity<Map> recall(String token, String entry, Map<String, ?> body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return exchange("/v1/encounters/" + entry + "/recall-orders", HttpMethod.POST, new HttpEntity<>(toJson(body), h), Map.class);
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map> listed(String token, String entry) {
        ResponseEntity<List> r = exchange("/v1/encounters/" + entry + "/recall-orders", HttpMethod.GET, authed(token), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> bookFollowUp(Clinic c, Visit v, int hour, boolean followUp) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.desk(), Map.of("patientId", v.patientId(),
                "doctorId", c.doctor().id().toString(), "isFollowUp", followUp,
                "startTime", followUpDay().atTime(LocalTime.of(hour, 0)).atZone(IST).toInstant().toString())), Map.class);
    }

    private LocalDate visitDate(Clinic c, Visit v) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT queue_date FROM queue_entries WHERE id = ?::uuid", LocalDate.class, v.entry()));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @Test
    void reviewInTwoWeeksCreatesAPendingRecallDueOnTheClinicVisitDatePlus14Fast() {
        Clinic c = clinic();
        // warm the create path on another visit (JIT, pools, idempotency store): the AC times the doctor's tap, not a cold JVM
        assertThat(recall(c.doc(), visit(c).entry(), Map.of("intervalDays", 7)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Visit v = visit(c);
        assertThat(listed(c.doc(), v.entry())).isEmpty(); // the visit summary shows "not set"

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = recall(c.doc(), v.entry(), Map.of("intervalDays", 14));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ms).as("create latency ms").isLessThan(300);
        LocalDate due = visitDate(c, v).plusDays(14);
        assertThat(r.getBody()).containsEntry("dueDate", due.toString()).containsEntry("status", "pending").containsEntry("intervalDays", 14);
        String recallId = (String) r.getBody().get("recallId");

        List<Map> shown = listed(c.doc(), v.entry());
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("recallId", recallId).containsEntry("dueDate", due.toString());

        Map<String, Object> row = su().queryForMap("SELECT source, doctor_id, patient_id, created_by FROM recall_orders WHERE id = ?::uuid", recallId);
        assertThat(row.get("source")).isEqualTo("consult");
        assertThat(row.get("doctor_id")).isEqualTo(c.doctor().id());
        assertThat(row.get("patient_id").toString()).isEqualTo(v.patientId());
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'recall_order.created' AND entity_id = ?::uuid",
                Integer.class, c.t().id(), recallId)).isEqualTo(1);
        String data = su().queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'engagement.recall.created.v1' "
                + "AND aggregate_id = ?::uuid", String.class, c.t().id(), recallId);
        assertThat(data).contains(recallId).contains(v.patientId()).contains(due.toString());

        // one recall per visit
        ResponseEntity<Map> again = recall(c.doc(), v.entry(), Map.of("intervalDays", 28));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "RECALL_EXISTS_FOR_ENCOUNTER");
    }

    @Test
    void bookingTheFollowUpMarksTheRecallBookedAndCancelsItsReminder() {
        Clinic c = clinic();
        Visit v = visit(c);
        String recallId = (String) recall(c.doc(), v.entry(), Map.of("intervalDays", 14)).getBody().get("recallId");
        // a reminder already requested about this recall (the COM-054 sender's subject) must not go out once booked
        UUID reminder = UUID.randomUUID();
        su().update("INSERT INTO notification_requests (id, tenant_id, purpose, subject_type, subject_id, recipient_patient_id, priority, "
                + "channel_policy, dedupe_key) VALUES (?, ?, 'recall_due', 'recall_order', ?::uuid, ?::uuid, 'BULK', 'WHATSAPP_THEN_SMS', ?)",
                reminder, c.t().id(), recallId, v.patientId(), "rc-" + reminder);

        ResponseEntity<Map> notFollowUp = bookFollowUp(c, v, 9, false);
        assertThat(notFollowUp.getStatusCode()).as("%s", notFollowUp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(listed(c.doc(), v.entry()).get(0)).containsEntry("status", "pending"); // an ordinary booking leaves the recall alone

        ResponseEntity<Map> booked = bookFollowUp(c, v, 10, true);
        assertThat(booked.getStatusCode()).as("%s", booked.getBody()).isEqualTo(HttpStatus.CREATED);
        Map shown = listed(c.doc(), v.entry()).get(0);
        assertThat(shown).containsEntry("status", "booked").containsEntry("bookedAppointmentId", booked.getBody().get("id"));
        assertThat(su().queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, reminder)).isEqualTo("cancelled");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'recall_order.booked' AND entity_id = ?::uuid",
                Integer.class, c.t().id(), recallId)).isEqualTo(1);
    }

    @Test
    void aFollowUpBookedBeforeTheChipIsTappedMakesTheRecallBookedAtOnce() {
        Clinic c = clinic();
        Visit v = visit(c);
        ResponseEntity<Map> booked = bookFollowUp(c, v, 11, true);
        assertThat(booked.getStatusCode()).as("%s", booked.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = recall(c.doc(), v.entry(), Map.of("intervalDays", 7, "reasonCode", "bp_review"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("status", "booked").containsEntry("bookedAppointmentId", booked.getBody().get("id"))
                .containsEntry("reasonCode", "bp_review");
    }

    @Test
    void onlyTheVisitDoctorSetsARecallWithAnIntervalInRangeAndACodedReason() {
        Clinic c = clinic();
        Visit v = visit(c);
        for (Integer bad : new Integer[]{0, 731, -3}) {
            ResponseEntity<Map> r = recall(c.doc(), v.entry(), Map.of("intervalDays", bad));
            assertThat(r.getStatusCode()).as("%s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody()).containsEntry("code", "INTERVAL_OUT_OF_RANGE");
        }
        assertThat(recall(c.doc(), v.entry(), new HashMap<>()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(recall(c.doc(), v.entry(), Map.of("intervalDays", 14, "reasonCode", "Uncontrolled diabetes, HbA1c 9")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST); // free text never becomes a reason code
        ResponseEntity<Map> other = recall(c.otherDoc(), v.entry(), Map.of("intervalDays", 14));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(other.getBody()).containsEntry("code", "NOT_DOCTOR_OF_RECORD");
        assertThat(recall(c.desk(), v.entry(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // no clinical:edit
        assertThat(recall(c.doc(), UUID.randomUUID().toString(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT count(*) FROM recall_orders WHERE tenant_id = ?", Integer.class, c.t().id())).isZero();

        // the boundaries are accepted
        assertThat(recall(c.doc(), v.entry(), Map.of("intervalDays", 730)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // T1-BUG-3 (IDC-001): an own-patients-only doctor gets the unknown-visit 404 on both endpoints, not the 403 that confirms the visit
    @Test
    void anOwnPatientsOnlyDoctorSeesAnotherDoctorsVisitAsMissing() {
        Clinic c = clinic();
        Visit v = visit(c);
        assertThat(recall(c.doc(), v.entry(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SeededStaff own = seedStaff(c.t(), c.doctorRole(), "rc" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        su().update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", own.id());
        String token = loginAndGetAccessToken(own);
        ResponseEntity<Map> missing = recall(token, UUID.randomUUID().toString(), Map.of("intervalDays", 14));
        ResponseEntity<Map> post = recall(token, v.entry(), Map.of("intervalDays", 14));
        ResponseEntity<Map> get = exchange("/v1/encounters/" + v.entry() + "/recall-orders", HttpMethod.GET, authed(token), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (ResponseEntity<Map> r : List.of(post, get)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            for (String k : new String[]{"type", "title", "detail"}) {
                assertThat(r.getBody().get(k)).as(k).isEqualTo(missing.getBody().get(k));
            }
        }
        assertThat(su().queryForObject("SELECT count(*) FROM recall_orders WHERE tenant_id = ?", Integer.class, c.t().id())).isEqualTo(1);
    }

    @Test
    void anotherClinicCanNeitherSeeNorSetThisVisitsRecall() {
        Clinic a = clinic();
        Visit v = visit(a);
        assertThat(recall(a.doc(), v.entry(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Clinic b = clinic();
        assertThat(recall(b.doc(), v.entry(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/encounters/" + v.entry() + "/recall-orders", HttpMethod.GET, authed(b.doc()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // RLS: tenant B's context sees no recall rows of tenant A
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM recall_orders", Integer.class))).isZero();
        assertThat(inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM recall_orders", Integer.class))).isEqualTo(1);
    }
}
