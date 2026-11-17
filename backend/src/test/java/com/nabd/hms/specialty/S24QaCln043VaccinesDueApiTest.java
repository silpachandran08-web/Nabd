package com.nabd.hms.specialty;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) CLN-043 'Vaccines due today': read scope, withdrawn consent, a non-doctor clinician, the desk. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCln043VaccinesDueApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 200_000);
    @Autowired ClinicClock clock;

    private static String phone() {
        return "+9190" + String.format("%08d", SEQ.incrementAndGet());
    }

    private record Clinic(SeededTenant t, SeededStaff docA, String a, SeededStaff docB, String desk, String nurse) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID doctorRole = seedRole(t.id(), "QPed", false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        UUID deskRole = seedRole(t.id(), "QDesk", false, fullGrant("queue"), fullGrant("patients"));
        UUID nurseRole = seedRole(t.id(), "QNurse", false, new ModuleGrant("clinical", true, false, false, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff a = ClinicianFixture.licensed(seedStaff(t, doctorRole, "q43a" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        SeededStaff b = ClinicianFixture.licensed(seedStaff(t, doctorRole, "q43b" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        new JdbcTemplate(superuserDataSource()).update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", a.id());
        return new Clinic(t, a, loginAndGetAccessToken(a), b,
                loginAndGetAccessToken(seedStaff(t, deskRole, "q43d" + SEQ.incrementAndGet() + "@q.com", phone(), false)),
                loginAndGetAccessToken(seedStaff(t, nurseRole, "q43n" + SEQ.incrementAndGet() + "@q.com", phone(), false)));
    }

    private UUID child(Clinic c) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Q43 Child', ?, ?, 'male') RETURNING id",
                UUID.class, c.t().id(), phone(), Date.valueOf(LocalDate.now().minusMonths(10))));
    }

    private void checkIn(Clinic c, UUID patient, UUID doctor) {
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.desk(), Map.of("patientId", patient.toString(), "doctorId", doctor.toString())), Map.class);
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private UUID recall(Clinic c, UUID patient) {
        LocalDate today = clock.clinicDate(c.t().id());
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO recall_orders (tenant_id, patient_id, source, reason_code, due_date, status) "
                + "VALUES (?,?,'vaccine','q43_dose',?,'pending') RETURNING id", UUID.class, c.t().id(), patient, Date.valueOf(today)));
    }

    private ResponseEntity<List> due(String token) {
        return exchange("/v1/recall-orders/vaccines-due-today", HttpMethod.GET, authed(token), List.class);
    }

    // C43-A01 + A02 + A03: own-scope doctor sees only own; withdrawn data-processing consent hides the child; non-doctor empty; desk 403
    @Test
    void c43a01_a02_a03_scopeConsentAndRoles() {
        Clinic c = clinic();
        UUID mine = child(c);
        checkIn(c, mine, c.docA().id());
        UUID mineRecall = recall(c, mine);
        UUID withdrawn = child(c);
        checkIn(c, withdrawn, c.docA().id());
        recall(c, withdrawn);
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'data_processing', now())",
                c.t().id(), withdrawn));
        UUID theirs = child(c);
        checkIn(c, theirs, c.docB().id());
        recall(c, theirs);

        ResponseEntity<List> a = due(c.a());
        ResponseEntity<List> nurse = due(c.nurse());
        int desk = exchange("/v1/recall-orders/vaccines-due-today", HttpMethod.GET, authed(c.desk()), Map.class).getStatusCode().value();
        System.out.println("C43-A01/A02 ownScopeDoctor=" + a.getBody() + " | C43 nurse=" + nurse.getStatusCode() + " " + nurse.getBody() + " | C43-A03 desk=" + desk);
        assertThat(a.getBody()).extracting(r -> ((Map) r).get("recallId")).containsExactly(mineRecall.toString());
        assertThat(nurse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(nurse.getBody()).isEmpty();
        assertThat(desk).isEqualTo(403);
    }
}
