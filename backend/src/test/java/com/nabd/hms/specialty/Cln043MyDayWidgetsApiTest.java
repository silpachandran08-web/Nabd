package com.nabd.hms.specialty;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLN-043: per-profile my-day config (widgets, at most 6 quick actions) and the 'Vaccines due today' data source. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln043MyDayWidgetsApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 500_000);

    @Autowired
    private ClinicClock clock;

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String actions(int n) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : ",").append("{\"id\":\"a").append(i).append("\",\"href\":\"/consult\",\"label\":{\"en\":\"A\"}}");
        return b.append("]").toString();
    }

    private static String config(String module, int quickActions) {
        return "{\"module\":\"" + module + "\",\"widgets\":[],\"quickActions\":" + actions(quickActions)
                + ",\"fieldSets\":[],\"templatePack\":null,\"myDay\":{\"widgets\":[\"photosDue\"]}}";
    }

    @Test
    void everyProfileShipsItsMyDayWidgetsAndAtMostSixQuickActions() {
        List<Map<String, Object>> rows = su().queryForList("SELECT code, config_version, jsonb_array_length(config->'quickActions') AS actions, "
                + "config->'myDay'->'widgets' AS widgets, config->'myDay'->>'visitTypeChips' AS chips, "
                + "config->'myDay'->>'ageInMonthsUnder2' AS months FROM specialty_profiles WHERE code IN "
                + "('GP','DERM','DENT','PED','ENT','ORTH','GYN','HOM','AYU') ORDER BY code");
        assertThat(rows).hasSize(9);
        for (Map<String, Object> r : rows) {
            assertThat((Integer) r.get("actions")).as("%s", r.get("code")).isBetween(1, 6);
            assertThat(r.get("widgets")).as("%s", r.get("code")).isNotNull();
            assertThat((Integer) r.get("config_version")).as("%s", r.get("code")).isGreaterThanOrEqualTo(2); // new config = new version
        }
        Map<String, Map<String, Object>> by = new java.util.HashMap<>();
        rows.forEach(r -> by.put((String) r.get("code"), r));
        assertThat(by.get("DERM").get("widgets").toString()).contains("photosDue");
        assertThat(by.get("DERM").get("chips")).isEqualTo("true");
        assertThat(by.get("PED").get("widgets").toString()).contains("vaccinesDueToday");
        assertThat(by.get("PED").get("months")).isEqualTo("true");
        assertThat(by.get("GP").get("chips")).isNull();
    }

    @Test
    void aSeventhQuickActionIsRejectedBySchemaValidation() {
        JdbcTemplate su = su();
        try {
            su.update("INSERT INTO specialty_profiles (code, name, config) VALUES ('CLNSIX', '{\"en\":\"Six\"}', ?::jsonb)", config("specialty_clnsix", 6));
            assertThat(su.queryForObject("SELECT jsonb_array_length(config->'quickActions') FROM specialty_profiles WHERE code = 'CLNSIX'", Integer.class)).isEqualTo(6);
            assertThatThrownBy(() -> su.update("UPDATE specialty_profiles SET config = ?::jsonb WHERE code = 'CLNSIX'", config("specialty_clnsix", 7)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> su.update("INSERT INTO specialty_profiles (code, name, config) VALUES ('CLNSEV', '{\"en\":\"Seven\"}', ?::jsonb)",
                    config("specialty_clnsev", 7))).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> su.update("UPDATE specialty_profiles SET config = jsonb_set(config, '{myDay}', '\"photosDue\"') WHERE code = 'CLNSIX'"))
                    .isInstanceOf(DataIntegrityViolationException.class); // myDay must be an object with a widgets array
        } finally {
            su.update("DELETE FROM specialty_profiles WHERE code IN ('CLNSIX', 'CLNSEV')");
        }
    }

    private record Clinic(SeededTenant t, SeededStaff doctor, String doc, String otherDoc, UUID otherDoctorId, String desk) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID doctorRole = seedRole(t.id(), "PedDoctor", false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        UUID deskRole = seedRole(t.id(), "PedDesk", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(t, doctorRole, "c43" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        SeededStaff other = ClinicianFixture.licensed(seedStaff(t, doctorRole, "c43" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String desk = loginAndGetAccessToken(seedStaff(t, deskRole, "c43" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        return new Clinic(t, doctor, loginAndGetAccessToken(doctor), loginAndGetAccessToken(other), other.id(), desk);
    }

    private UUID patient(Clinic c) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) "
                + "VALUES (?, 'C43 Child', ?, ?, 'female') RETURNING id", UUID.class, c.t().id(), phone(), Date.valueOf(LocalDate.now().minusMonths(14))));
    }

    private void checkIn(Clinic c, UUID patientId, UUID doctorId) {
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.desk(), Map.of(
                "patientId", patientId.toString(), "doctorId", doctorId.toString())), Map.class);
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    private void book(Clinic c, UUID patientId, Instant start, String status) {
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?)",
                c.t().id(), patientId, c.doctor().id(), Timestamp.from(start), Timestamp.from(start.plusSeconds(900)), status));
    }

    private UUID recall(Clinic c, UUID patientId, String source, LocalDate due, String status) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO recall_orders (tenant_id, patient_id, source, reason_code, due_date, status) "
                + "VALUES (?,?,?,?,?,?) RETURNING id", UUID.class, c.t().id(), patientId, source, "test_vaccine", Date.valueOf(due), status));
    }

    private List<Map> due(String token) {
        ResponseEntity<List> r = exchange("/v1/recall-orders/vaccines-due-today", HttpMethod.GET, authed(token), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void vaccinesDueTodayListsOnlyTheDoctorsBookedOrArrivedPatientsWithAnOpenVaccineRecallDue() {
        Clinic c = clinic();
        LocalDate today = clock.clinicDate(c.t().id());
        Instant dayStart = clock.dayStart(c.t().id(), today);

        UUID arrived = patient(c);
        checkIn(c, arrived, c.doctor().id());
        UUID arrivedRecall = recall(c, arrived, "vaccine", today, "pending");

        UUID booked = patient(c);
        book(c, booked, dayStart.plusSeconds(3 * 3600), "scheduled");
        UUID overdueRecall = recall(c, booked, "vaccine", today.minusDays(3), "messaged");

        UUID notHereToday = patient(c); // due today but no visit with this doctor today
        recall(c, notHereToday, "vaccine", today, "pending");
        UUID consultRecall = patient(c); // a consult follow-up recall is not a vaccine
        checkIn(c, consultRecall, c.doctor().id());
        recall(c, consultRecall, "consult", today, "pending");
        UUID later = patient(c); // not due yet
        checkIn(c, later, c.doctor().id());
        recall(c, later, "vaccine", today.plusDays(1), "pending");
        UUID given = patient(c); // already done
        checkIn(c, given, c.doctor().id());
        recall(c, given, "vaccine", today, "done");
        UUID cancelledBooking = patient(c);
        book(c, cancelledBooking, dayStart.plusSeconds(4 * 3600), "cancelled");
        recall(c, cancelledBooking, "vaccine", today, "pending");
        UUID tomorrow = patient(c); // booked, but on the next clinic day
        book(c, tomorrow, clock.dayStart(c.t().id(), today.plusDays(1)).plusSeconds(3600), "scheduled");
        recall(c, tomorrow, "vaccine", today, "pending");
        UUID otherDoctors = patient(c);
        checkIn(c, otherDoctors, c.otherDoctorId());
        recall(c, otherDoctors, "vaccine", today, "pending");

        List<Map> rows = due(c.doc());
        assertThat(rows).extracting(r -> r.get("recallId")).containsExactly(overdueRecall.toString(), arrivedRecall.toString());
        assertThat(rows.get(0)).containsEntry("patientId", booked.toString()).containsEntry("arrived", false)
                .containsEntry("dueDate", today.minusDays(3).toString()).containsEntry("reasonCode", "test_vaccine");
        assertThat(rows.get(1)).containsEntry("patientId", arrived.toString()).containsEntry("arrived", true);

        assertThat(due(c.otherDoc())).extracting(r -> r.get("patientId")).containsExactly(otherDoctors.toString());
        assertThat(due(clinic().doc())).isEmpty(); // another clinic sees none of these
        // the desk has no clinical:view: 403, which the widget treats as 'hide'
        assertThat(exchange("/v1/recall-orders/vaccines-due-today", HttpMethod.GET, authed(c.desk()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
