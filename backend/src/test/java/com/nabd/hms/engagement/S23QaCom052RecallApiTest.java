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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S23 QA (t1) COM-052: own-patients scope on the recall read, Idempotency-Key replay, due date = clinic visit date + 14. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S23QaCom052RecallApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 700_000);

    private record Clinic(SeededTenant t, SeededStaff doctor, String doc, UUID doctorRole, String desk) {
    }

    private static String phone() {
        return "+9191" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'Asia/Kolkata' WHERE id = ?", t.id());
        UUID doctorRole = seedRole(t.id(), "QDoctor", false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false), new ModuleGrant("patients", true, false, false, false, false, false, false));
        UUID deskRole = seedRole(t.id(), "QDesk", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(t, doctorRole, "q52" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String desk = loginAndGetAccessToken(seedStaff(t, deskRole, "q52d" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        return new Clinic(t, doctor, loginAndGetAccessToken(doctor), doctorRole, desk);
    }

    private String visit(Clinic c) {
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.desk(), Map.of("name", "Q52 Patient " + SEQ.incrementAndGet(),
                "phone", phone(), "dob", "1969-09-09", "gender", "male")), Map.class).getBody().get("id");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.desk(), Map.of(
                "patientId", patientId, "doctorId", c.doctor().id().toString())), Map.class);
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) q.getBody().get("id");
    }

    private ResponseEntity<Map> recall(String token, String entry, String key, Map<String, ?> body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        try {
            return exchange("/v1/encounters/" + entry + "/recall-orders", HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), h), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // C52-A01 (e10 flagged): a doctor limited to own patients reads another doctor's visit recall
    @Test
    void c52a01_ownPatientsOnlyDoctorCannotReadAnotherDoctorsVisitRecall() {
        Clinic c = clinic();
        String entry = visit(c);
        assertThat(recall(c.doc(), entry, UUID.randomUUID().toString(), Map.of("intervalDays", 14)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SeededStaff own = ClinicianFixture.licensed(seedStaff(c.t(), c.doctorRole(), "q52o" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", own.id());
        String ownToken = loginAndGetAccessToken(own);
        // control: the same scoped doctor is refused the visit's other clinical reads
        ResponseEntity<String> header = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.GET, authed(ownToken), String.class);
        ResponseEntity<String> r = exchange("/v1/encounters/" + entry + "/recall-orders", HttpMethod.GET, authed(ownToken), String.class);
        System.out.println("C52-A01 own-scope rx read=" + header.getStatusCode() + " recall read=" + r.getStatusCode() + " " + r.getBody());
        assertThat(r.getStatusCode()).as("recall of a patient outside the doctor's scope").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // C52-A02: a retried tap with the same Idempotency-Key is a replay, not 409
    @Test
    void c52a02_sameKeyReplayReturnsTheSameRecallAndOneRow() {
        Clinic c = clinic();
        String entry = visit(c);
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = recall(c.doc(), entry, key, Map.of("intervalDays", 14));
        ResponseEntity<Map> b = recall(c.doc(), entry, key, Map.of("intervalDays", 14));
        System.out.println("C52-A02 first=" + a.getStatusCode() + " replay=" + b.getStatusCode() + " replayed=" + b.getHeaders().getFirst("Idempotent-Replayed"));
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getBody().get("recallId")).isEqualTo(a.getBody().get("recallId"));
        assertThat(su().queryForObject("SELECT count(*) FROM recall_orders WHERE tenant_id = ?", Integer.class, c.t().id())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'recall_order.created'", Integer.class, c.t().id())).isEqualTo(1);
    }

    // C52-A03: due = the clinic's visit date + 14, also when the stored visit date is not today's UTC date
    @Test
    void c52a03_dueDateIsTheClinicVisitDatePlus14() {
        Clinic c = clinic();
        String entry = visit(c);
        LocalDate visitDate = LocalDate.of(2026, 12, 31); // year boundary
        su().update("UPDATE queue_entries SET queue_date = ? WHERE id = ?::uuid", visitDate, entry);
        ResponseEntity<Map> r = recall(c.doc(), entry, UUID.randomUUID().toString(), Map.of("intervalDays", 14));
        System.out.println("C52-A03 " + r.getStatusCode() + " " + r.getBody());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("dueDate", "2027-01-14");
        List<Map> listed = exchange("/v1/encounters/" + entry + "/recall-orders", HttpMethod.GET, authed(c.doc()), List.class).getBody();
        assertThat(listed).singleElement().satisfies(m -> assertThat(m).containsEntry("dueDate", "2027-01-14"));
    }
}
