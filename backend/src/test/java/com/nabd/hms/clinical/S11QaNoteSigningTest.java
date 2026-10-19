package com.nabd.hms.clinical;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S11 CLN-006): only the licensed visit doctor (or a licensed approver) signs or amends the visit note;
 * a signed note is final in the database; tooth history comes from dental_chart_events. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S11QaNoteSigningTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 700_000);

    private static String phone() {
        return "+9194" + String.format("%08d", SEQ.incrementAndGet());
    }

    private record Visit(SeededTenant t, SeededStaff doctor, String doc, String nurse, String approver, String otherDoc,
                         String desk, String patientId, String entry) {
    }

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "qa1n" + SEQ.incrementAndGet() + "@q.com", phone(), false);
    }

    private Visit visit() {
        SeededTenant t = seedTenant();
        ModuleGrant clinicalEdit = new ModuleGrant("clinical", true, true, true, false, false, false, false);
        ModuleGrant queueView = new ModuleGrant("queue", true, false, false, false, false, false, false);
        UUID doctorRole = seedRole(t.id(), "QaDoctor", false, clinicalEdit, queueView);
        UUID nurseRole = seedRole(t.id(), "QaNurse", false, clinicalEdit, queueView);
        UUID approverRole = seedRole(t.id(), "QaApprover", false, fullGrant("clinical"), queueView);
        UUID deskRole = seedRole(t.id(), "QaDesk", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff doctor = ClinicianFixture.licensed(staff(t, doctorRole));
        String doc = loginAndGetAccessToken(doctor);
        String nurse = loginAndGetAccessToken(ClinicianFixture.licensed(staff(t, nurseRole))); // even a licensed nurse is not the visit doctor
        String approver = loginAndGetAccessToken(ClinicianFixture.licensed(staff(t, approverRole)));
        String other = loginAndGetAccessToken(ClinicianFixture.licensed(staff(t, doctorRole)));
        String desk = loginAndGetAccessToken(staff(t, deskRole));
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(desk, Map.of("name", "Qa Note " +
                Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(), "dob", LocalDate.of(1970, 5, 5).plusDays(SEQ.get() % 9000).toString(),
                "gender", "female")), Map.class);
        String patientId = (String) p.getBody().get("id");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(desk, Map.of(
                "patientId", patientId, "doctorId", doctor.id().toString())), Map.class);
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
        String entry = (String) q.getBody().get("id");
        assertThat(exchange("/v1/clinical/notes/" + entry, HttpMethod.PATCH, authedJsonBody(doc, Map.of(
                "subjective", "Cough 3 days", "assessment", "Viral URTI", "plan", "Fluids", "diagnosis", "URTI")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Visit(t, doctor, doc, nurse, approver, other, desk, patientId, entry);
    }

    private ResponseEntity<Map> sign(String tok, String entry) {
        return exchange("/v1/clinical/notes/" + entry + "/sign", HttpMethod.POST, authed(tok), Map.class);
    }

    private ResponseEntity<Map> amend(String tok, String entry, String reason, String assessment) {
        Map<String, Object> b = new HashMap<>();
        if (reason != null) b.put("reason", reason);
        b.put("assessment", assessment);
        return exchange("/v1/clinical/notes/" + entry + "/amendments", HttpMethod.POST, authedJsonBody(tok, b), Map.class);
    }

    private Map<?, ?> note(String tok, String entry) {
        return exchange("/v1/clinical/notes/" + entry, HttpMethod.GET, authed(tok), Map.class).getBody();
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static void assert403(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).as("%s", r.getBody()).endsWith("/" + code);
    }

    // AC1
    @Test
    void nurseWithClinicalEditCannotSignTheDoctorsNote() {
        Visit v = visit();
        assert403(sign(v.nurse(), v.entry()), "not-prescriber");
        assert403(sign(v.otherDoc(), v.entry()), "not-prescriber");
        assertThat(note(v.doc(), v.entry()).get("status")).isEqualTo("draft");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'clinical_note.signed'",
                Integer.class, v.t().id())).isZero();
    }

    // AC2 (+ 409 already-signed)
    @Test
    void visitDoctorSignsSignerStoredAuditedUnderTwoSecondsAndASecondSignIs409() {
        Visit v = visit();
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = sign(v.doc(), v.entry());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ms).isLessThan(2_000);
        assertThat(r.getBody()).containsEntry("status", "signed").containsEntry("signedBy", v.doctor().id().toString());
        assertThat(note(v.nurse(), v.entry()).get("signedBy")).isEqualTo(v.doctor().id().toString());
        assertThat(su().queryForObject("SELECT signed_by FROM clinical_notes WHERE queue_entry_id = ?::uuid", UUID.class, v.entry()))
                .isEqualTo(v.doctor().id());
        Map<String, Object> audit = su().queryForMap("SELECT actor_id, after::text AS after FROM audit_log WHERE tenant_id = ? " +
                "AND action = 'clinical_note.signed'", v.t().id());
        assertThat(audit.get("actor_id")).isEqualTo(v.doctor().id());
        assertThat((String) audit.get("after")).contains("licenceNo");
        ResponseEntity<Map> again = sign(v.doc(), v.entry());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody().get("type"))).containsIgnoringCase("already-signed");
    }

    @Test
    void licensedApproverMaySignAnotherDoctorsNote() {
        Visit v = visit();
        ResponseEntity<Map> r = sign(v.approver(), v.entry());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("signedBy")).isNotEqualTo(v.doctor().id().toString());
    }

    // CLN-005 licence rule wired into note sign/amend at integration
    @Test
    void visitDoctorWithoutLicenceOrWithExpiredLicenceCannotSignTheNote() {
        Visit v = visit();
        su().update("UPDATE staff SET is_clinician = false WHERE tenant_id = ? AND id = ?", v.t().id(), v.doctor().id());
        assert403(sign(v.doc(), v.entry()), "not-licensed-clinician");
        su().update("UPDATE staff SET is_clinician = true, licence_expires_on = current_date - 2 WHERE tenant_id = ? AND id = ?",
                v.t().id(), v.doctor().id());
        assert403(sign(v.doc(), v.entry()), "licence-expired");
        assertThat(note(v.doc(), v.entry()).get("status")).isEqualTo("draft");
        su().update("UPDATE staff SET licence_expires_on = NULL WHERE tenant_id = ? AND id = ?", v.t().id(), v.doctor().id());
        assertThat(sign(v.doc(), v.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // the amendment path applies the same rule
        su().update("UPDATE staff SET is_clinician = false WHERE tenant_id = ? AND id = ?", v.t().id(), v.doctor().id());
        assert403(amend(v.doc(), v.entry(), "Correcting the plan", "Bacterial"), "not-licensed-clinician");
    }

    @Test
    void breakGlassSessionCannotSignTheNote() {
        Visit v = visit();
        UUID bgRole = seedRole(v.t().id(), "QaBg", false, new ModuleGrant("clinical", true, true, true, false, false, false, false, true),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        String tok = loginAndGetAccessToken(ClinicianFixture.licensed(staff(v.t(), bgRole)));
        ResponseEntity<Map> act = activateBreakGlass(tok, "Patient collapsed, treating doctor unavailable", null);
        assertThat(act.getStatusCode()).as("%s", act.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = sign((String) act.getBody().get("accessToken"), v.entry());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(note(v.doc(), v.entry()).get("status")).isEqualTo("draft");
    }

    // AC3
    @Test
    void signedNoteIsFinalInTheDatabase() {
        Visit v = visit();
        assertThat(sign(v.doc(), v.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String col : List.of("subjective", "objective", "assessment", "plan", "diagnosis")) {
            assertThatThrownBy(() -> su().update("UPDATE clinical_notes SET " + col + " = 'tampered' WHERE queue_entry_id = ?::uuid", v.entry()))
                    .as(col).hasStackTraceContaining("clinical_notes_signed_is_final");
        }
        // the API PATCH of a signed note does not change it either
        ResponseEntity<Map> patch = exchange("/v1/clinical/notes/" + v.entry(), HttpMethod.PATCH, authedJsonBody(v.doc(),
                Map.of("assessment", "tampered")), Map.class);
        assertThat(patch.getStatusCode().is2xxSuccessful()).as("%s", patch.getBody()).isFalse();
        assertThat(note(v.doc(), v.entry()).get("assessment")).isEqualTo("Viral URTI");
    }

    @Test
    void amendmentsNeedAReasonASignedNoteAndTheVisitDoctorOrApprover() {
        Visit v = visit();
        ResponseEntity<Map> early = amend(v.doc(), v.entry(), "Too early", "Bacterial");
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(early.getBody().get("type"))).contains("note-not-signed");
        assertThat(sign(v.doc(), v.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assert403(amend(v.nurse(), v.entry(), "Nurse correction", "Bacterial"), "not-prescriber");
        assert403(amend(v.otherDoc(), v.entry(), "Other doctor edit", "Bacterial"), "not-prescriber");
        assertThat(amend(v.doc(), v.entry(), null, "Bacterial").getStatusCode().is4xxClientError()).isTrue();
        assertThat(amend(v.doc(), v.entry(), "abcd", "Bacterial").getStatusCode().is4xxClientError()).isTrue(); // 4 < 5
        assertThat(amend(v.doc(), v.entry(), "x".repeat(501), "Bacterial").getStatusCode().is4xxClientError()).isTrue();

        ResponseEntity<Map> ok = amend(v.doc(), v.entry(), "abcde", "Bacterial"); // boundary 5
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(String.valueOf(ok.getBody())).contains(v.doctor().id().toString());
        assertThat(amend(v.approver(), v.entry(), "y".repeat(500), "Bacterial pneumonia").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // the original stays as signed; the change lives only in note_amendments
        assertThat(note(v.doc(), v.entry()).get("assessment")).isEqualTo("Viral URTI");
        assertThat(su().queryForObject("SELECT count(*) FROM note_amendments na JOIN clinical_notes n ON n.id = na.note_id " +
                "WHERE n.queue_entry_id = ?::uuid", Integer.class, v.entry())).isEqualTo(2);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'clinical_note.amended'",
                Integer.class, v.t().id())).isEqualTo(2);
    }

    @Test
    void doctorOfAnotherTenantCannotSignOrAmend() {
        Visit a = visit();
        Visit b = visit();
        assertThat(sign(b.approver(), a.entry()).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(note(a.doc(), a.entry()).get("status")).isEqualTo("draft");
        assertThat(sign(a.doc(), a.entry()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amend(b.approver(), a.entry(), "Cross tenant edit", "x").getStatusCode().is2xxSuccessful()).isFalse();
    }

    // AC4
    @Test
    void toothHistoryOfThreeChangesComesFromDentalChartEventsInOrder() {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(staff(t, seedFullAccessRole(t.id())));
        String p = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "Qa Dent " +
                Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(), "dob", "1988-02-02", "gender", "male")), Map.class).getBody().get("id");
        String chart = "/v1/specialty/dental/patients/" + p + "/chart/";
        String entryId = null;
        for (String s : List.of("decayed", "root_canal", "crown")) {
            ResponseEntity<Map> r = exchange(chart + 26, HttpMethod.PATCH, authedJsonBody(tok, Map.of("status", s)), Map.class);
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            entryId = (String) r.getBody().get("id");
        }
        // delete the audit trail: the history must not depend on it
        try (Connection c = superuserConnection(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("DELETE FROM audit_log WHERE tenant_id = '" + t.id() + "' AND entity_id = '" + entryId + "'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ResponseEntity<List> h = exchange(chart + "entries/" + entryId + "/history", HttpMethod.GET, authed(tok), List.class);
        assertThat(h.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> rows = h.getBody();
        assertThat(rows).hasSize(3);
        assertThat(String.valueOf(rows.get(0).get("after"))).contains("decayed");
        assertThat(String.valueOf(rows.get(1).get("after"))).contains("root_canal");
        assertThat(String.valueOf(rows.get(2).get("after"))).contains("crown");
    }

    // AC5
    @Test
    void backfillCopiesEveryHistoricDentalAuditRow() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff dentist = staff(t, seedFullAccessRole(t.id()));
        String tok = loginAndGetAccessToken(dentist);
        String p = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "Qa Hist " +
                Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(), "dob", "1979-03-03", "gender", "male")), Map.class).getBody().get("id");
        UUID entry = UUID.randomUUID();
        JdbcTemplate s = su();
        s.update("INSERT INTO dental_chart_entries (id, tenant_id, patient_id, tooth_number, status, updated_by) VALUES (?,?,?::uuid,14,'filled',?)",
                entry, t.id(), p, dentist.id());
        String ins = "INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type, entity_id, before, after) " +
                "VALUES (?, 'staff', ?, 'Dentist', 'Owner', ?, 'dental_chart_entries', ?, ?::jsonb, ?::jsonb)";
        String a = "{\"toothNumber\":14,\"status\":\"decayed\",\"isSupernumerary\":false}";
        String b = "{\"toothNumber\":14,\"status\":\"filled\",\"isSupernumerary\":false}";
        s.update(ins, t.id(), dentist.id(), "dental_chart.create", entry, null, a);
        s.update(ins, t.id(), dentist.id(), "dental_chart.update", entry, a, b);

        String sql = Files.readString(Path.of("../db/migrations/V1232__dental_chart_events_backfill.sql"));
        try (Connection mig = dataSourceFor(MIGRATOR_ROLE, MIGRATOR_ROLE_PW).getConnection()) {
            mig.setAutoCommit(false);
            try {
                try (Statement st = mig.createStatement()) {
                    st.execute(sql);
                    st.execute("SELECT set_config('app.tenant_id', '" + t.id() + "', true)");
                }
                List<String> got = new ArrayList<>();
                try (PreparedStatement ps = mig.prepareStatement("SELECT event, after->>'status' FROM dental_chart_events " +
                        "WHERE tenant_id = ? AND entry_id = ? ORDER BY occurred_at")) {
                    ps.setObject(1, t.id());
                    ps.setObject(2, entry);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) got.add(rs.getString(1) + ":" + rs.getString(2));
                    }
                }
                assertThat(got).containsExactly("set:decayed", "set:filled");
            } finally {
                mig.rollback();
            }
        }
    }
}
