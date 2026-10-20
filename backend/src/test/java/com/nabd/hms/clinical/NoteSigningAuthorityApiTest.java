package com.nabd.hms.clinical;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLN-006: only the visit doctor (or a clinical:approve holder) signs or amends a note; signer stored and audited. */
class NoteSigningAuthorityApiTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    com.nabd.hms.common.ClinicClock clinicClock;

    // CLN-005 via CLN-006: signing/amending a note needs a registered clinician; every staff member seeded here is one.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private record Visit(SeededTenant tenant, String ownerToken, SeededStaff doctor, String doctorToken,
                         String nurseToken, String otherDoctorToken, String queueEntryId) {
    }

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    /** Owner (full grants incl. clinical:approve), visit doctor, a second doctor, and a nurse (clinical view+edit, no approve). */
    private Visit seedVisit(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "own" + tag + "@a.com", "+91960006" + tag + "0", false);
        UUID doctorRole = seedRole(tenant.id(), "Doctor", false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        UUID nurseRole = seedRole(tenant.id(), "Nurse", false, new ModuleGrant("clinical", true, true, true, false, false, false, false),
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff doctor = seedStaff(tenant, doctorRole, "doc" + tag + "@a.com", "+91960006" + tag + "1", false);
        SeededStaff other = seedStaff(tenant, doctorRole, "doc2" + tag + "@a.com", "+91960006" + tag + "2", false);
        SeededStaff nurse = seedStaff(tenant, nurseRole, "nurse" + tag + "@a.com", "+91960006" + tag + "3", false);
        String ownerToken = loginAndGetAccessToken(owner);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)),
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        ResponseEntity<Map> patient = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "name", "P" + tag, "phone", "+919999960" + tag + "0", "dob", "1990-01-01", "gender", "female")), Map.class);
        ResponseEntity<Map> entry = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "patientId", patient.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        String queueEntryId = (String) entry.getBody().get("id");
        String doctorToken = loginAndGetAccessToken(doctor);
        ResponseEntity<Map> draft = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH,
                authedJsonBody(doctorToken, Map.of("assessment", "Viral fever", "diagnosis", "URTI")), Map.class);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Visit(tenant, ownerToken, doctor, doctorToken, loginAndGetAccessToken(nurse),
                loginAndGetAccessToken(other), queueEntryId);
    }

    private ResponseEntity<Map> sign(String token, String queueEntryId) {
        return exchange("/v1/clinical/notes/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
    }

    private ResponseEntity<Map> amend(String token, String queueEntryId, Map<String, Object> body) {
        return exchange("/v1/clinical/notes/" + queueEntryId + "/amendments", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private int auditCount(UUID tenantId, String action, String noteId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?::uuid",
                Integer.class, tenantId, action, noteId));
    }

    @Test
    void nurseWithClinicalEditCannotSignTheDoctorsVisitNote() {
        Visit v = seedVisit("01");

        ResponseEntity<Map> resp = sign(v.nurseToken(), v.queueEntryId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("not-prescriber");
        ResponseEntity<Map> note = exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.GET, authed(v.nurseToken()), Map.class);
        assertThat(note.getBody().get("status")).isEqualTo("draft");
        assertThat(note.getBody().get("signedBy")).isNull();
    }

    @Test
    void anotherDoctorWithoutApproveCannotSign() {
        Visit v = seedVisit("02");

        ResponseEntity<Map> resp = sign(v.otherDoctorToken(), v.queueEntryId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("not-prescriber");
    }

    @Test
    void visitDoctorSignsAndTheSignerIsStoredAuditedAndPublishedWithinTwoSeconds() {
        Visit v = seedVisit("03");

        long started = System.nanoTime();
        ResponseEntity<Map> signed = sign(v.doctorToken(), v.queueEntryId());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsedMs).isLessThan(2000);
        assertThat(signed.getBody().get("signedAt")).isNotNull();
        ResponseEntity<Map> read = exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.GET, authed(v.nurseToken()), Map.class);
        assertThat(read.getBody().get("status")).isEqualTo("signed");
        assertThat(read.getBody().get("signedBy")).isEqualTo(v.doctor().id().toString());
        String noteId = (String) read.getBody().get("id");
        assertThat(auditCount(v.tenant().id(), "clinical_note.signed", noteId)).isEqualTo(1);
        Integer events = inTenantTx(v.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'clinical.note.signed.v1' " +
                        "AND payload ->> 'noteId' = ? AND payload ->> 'signedBy' = ?",
                Integer.class, v.tenant().id(), noteId, v.doctor().id().toString()));
        assertThat(events).isEqualTo(1);

        ResponseEntity<Map> again = sign(v.doctorToken(), v.queueEntryId());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().contains("already-signed");
        assertThat(auditCount(v.tenant().id(), "clinical_note.signed", noteId)).isEqualTo(1);
    }

    @Test
    void approverMaySignAnotherDoctorsVisitAndIsRecordedAsSigner() {
        Visit v = seedVisit("04");

        ResponseEntity<Map> signed = sign(v.ownerToken(), v.queueEntryId());

        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("signedBy")).isNotEqualTo(v.doctor().id().toString()).isNotNull();
    }

    @Test
    void signedNoteIsFinalInTheDatabaseAndOnlyAmendmentsChangeIt() {
        Visit v = seedVisit("05");
        sign(v.doctorToken(), v.queueEntryId());

        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        assertThatThrownBy(() -> su.update("UPDATE clinical_notes SET assessment = 'tampered' WHERE queue_entry_id = ?::uuid",
                v.queueEntryId())).hasMessageContaining("clinical note is signed");
        assertThatThrownBy(() -> su.update("UPDATE clinical_notes SET signed_by = NULL WHERE queue_entry_id = ?::uuid",
                v.queueEntryId())).hasMessageContaining("clinical note is signed");
        ResponseEntity<Map> patch = exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.PATCH,
                authedJsonBody(v.doctorToken(), Map.of("assessment", "edit after sign")), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> amended = amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "Typo in diagnosis", "diagnosis", "Influenza"));
        assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(amended.getBody().get("amendedBy")).isEqualTo(v.doctor().id().toString());
        assertThat(amended.getBody().get("id")).isNotNull();
        assertThat(amended.getBody().get("createdAt")).isNotNull();
        ResponseEntity<Map> note = exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.GET, authed(v.doctorToken()), Map.class);
        assertThat(note.getBody().get("assessment")).isEqualTo("Viral fever");
        assertThat(note.getBody().get("diagnosis")).isEqualTo("URTI");
        assertThat(auditCount(v.tenant().id(), "clinical_note.amended", (String) note.getBody().get("id"))).isEqualTo(1);
    }

    @Test
    void amendmentsAreRefusedForNurseOtherDoctorDraftNoteAndBadReason() {
        Visit v = seedVisit("06");

        ResponseEntity<Map> onDraft = amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "Too early"));
        assertThat(onDraft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onDraft.getBody().get("type")).asString().contains("note-not-signed");

        sign(v.doctorToken(), v.queueEntryId());
        ResponseEntity<Map> nurse = amend(v.nurseToken(), v.queueEntryId(), Map.of("reason", "Nurse correction"));
        assertThat(nurse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nurse.getBody().get("type")).asString().contains("not-prescriber");
        ResponseEntity<Map> other = amend(v.otherDoctorToken(), v.queueEntryId(), Map.of("reason", "Other doctor"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "four")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "x".repeat(501))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(amend(v.doctorToken(), v.queueEntryId(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "five!")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "y".repeat(500))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(amend(v.ownerToken(), v.queueEntryId(), Map.of("reason", "Approver addendum")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List> list = exchange("/v1/clinical/notes/" + v.queueEntryId() + "/amendments", HttpMethod.GET,
                authed(v.nurseToken()), List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(3);
    }

    @Test
    void unlicensedOrExpiredVisitDoctorCannotSignOrAmendAndTheLicenceIsAudited() {
        Visit v = seedVisit("09");
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("UPDATE staff SET is_clinician = false WHERE id = ?", v.doctor().id());
        ResponseEntity<Map> unlicensed = sign(v.doctorToken(), v.queueEntryId());
        assertThat(unlicensed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(unlicensed.getBody().get("type")).asString().contains("not-licensed-clinician");

        // yesterday on the clinic's calendar (ClinicClock), not the DB session's current_date: those differ near midnight
        su.update("UPDATE staff SET is_clinician = true, licence_expires_on = ? WHERE id = ?",
                java.sql.Date.valueOf(clinicClock.today(v.tenant().id()).minusDays(1)), v.doctor().id());
        ResponseEntity<Map> expired = sign(v.doctorToken(), v.queueEntryId());
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(expired.getBody().get("type")).asString().contains("licence-expired");

        su.update("UPDATE staff SET licence_expires_on = NULL WHERE id = ?", v.doctor().id());
        ResponseEntity<Map> signed = sign(v.doctorToken(), v.queueEntryId());
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String regNo = su.queryForObject("SELECT professional_registration_no FROM staff WHERE id = ?", String.class, v.doctor().id());
        assertThat(inTenantTx(v.tenant().id(), () -> jdbc.queryForObject(
                "SELECT after ->> 'licenceNo' FROM audit_log WHERE tenant_id = ? AND action = 'clinical_note.signed' AND entity_id = ?::uuid",
                String.class, v.tenant().id(), signed.getBody().get("id")))).isEqualTo(regNo);

        su.update("UPDATE staff SET is_clinician = false WHERE id = ?", v.doctor().id());
        ResponseEntity<Map> amend = amend(v.doctorToken(), v.queueEntryId(), Map.of("reason", "Unlicensed amend"));
        assertThat(amend.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(amend.getBody().get("type")).asString().contains("not-licensed-clinician");
    }

    @Test
    void doctorOfAnotherTenantCannotSignOrAmend() {
        Visit a = seedVisit("07");
        Visit b = seedVisit("08");

        assertThat(sign(b.doctorToken(), a.queueEntryId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        sign(a.doctorToken(), a.queueEntryId());
        assertThat(amend(b.ownerToken(), a.queueEntryId(), Map.of("reason", "Cross tenant")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> read = exchange("/v1/clinical/notes/" + a.queueEntryId(), HttpMethod.GET, authed(a.doctorToken()), Map.class);
        assertThat(read.getBody().get("signedBy")).isEqualTo(a.doctor().id().toString());
    }
}
