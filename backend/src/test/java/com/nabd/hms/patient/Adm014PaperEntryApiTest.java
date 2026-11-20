package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-014: paper cards typed in through POST /v1/patients (source paper_import), the data-gate read and the duplicate rules. */
class Adm014PaperEntryApiTest extends ApiTestBase {

    private static final int THIS_YEAR = LocalDate.now(ZoneOffset.UTC).getYear(); // seeded clinics run on UTC

    private record Desk(SeededTenant t, String token) {
    }

    private Desk desk(ModuleGrant... grants) {
        SeededTenant t = seedTenant();
        UUID role = grants.length == 0 ? seedFullAccessRole(t.id()) : seedRole(t.id(), "Receptionist", false, grants);
        return new Desk(t, loginAndGetAccessToken(seedStaff(t, role, "adm014-" + UUID.randomUUID() + "@a.com", phone(), false)));
    }

    private static ModuleGrant patients(boolean create) {
        return new ModuleGrant("patients", true, create, false, false, false, false, false);
    }

    private static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    private static Map<String, Object> card(String phone) {
        return new HashMap<>(Map.of("name", "Card " + UUID.randomUUID().toString().substring(0, 8), "mobile", phone,
                "yearOfBirth", THIS_YEAR - 40, "gender", "female", "source", "paper_import"));
    }

    private ResponseEntity<Map> post(Desk d, Map<String, Object> body) {
        return exchange("/v1/patients", HttpMethod.POST, authedJsonBody(d.token(), body), Map.class);
    }

    private int patients(Desk d) {
        return inTenantTx(d.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private static String fieldError(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return ((List<Map<String, String>>) r.getBody().get("errors")).get(0).get("field");
    }

    @Test
    void paperCardIsSavedTaggedYearOnlyWithItsNote_andAudited_withoutCardContentInTheAuditRow() {
        Desk d = desk(patients(true)); // a receptionist: patients view + create, no setup access
        Map<String, Object> body = card(phone());
        body.put("note", "  Last visit March 2024, Dr Rao  ");

        ResponseEntity<Map> created = post(d, body);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) created.getBody().get("id"));
        Map<String, Object> row = inTenantTx(d.t().id(), () -> jdbc.queryForMap(
                "SELECT registration_source, dob::text AS dob, dob_year_only, paper_note, is_synthetic FROM patients WHERE id = ?", id));
        assertThat(row).containsEntry("registration_source", "paper_import").containsEntry("dob", (THIS_YEAR - 40) + "-01-01")
                .containsEntry("dob_year_only", true).containsEntry("paper_note", "Last visit March 2024, Dr Rao").containsEntry("is_synthetic", false);
        String audit = inTenantTx(d.t().id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE entity_id = ? AND action = 'patient.paper_imported'", String.class, id));
        assertThat(audit).contains("paper_import").doesNotContain("Rao").doesNotContain((String) body.get("name"));

        Map<?, ?> detail = exchange("/v1/patients/" + id, HttpMethod.GET, authed(d.token()), Map.class).getBody();
        assertThat(detail.get("dobYearOnly")).isEqualTo(true);
        assertThat(detail.get("paperNote")).isEqualTo("Last visit March 2024, Dr Rao");
    }

    @Test
    void aFullDateTypedLaterClearsTheYearOnlyMark_andDeskRegistrationsNeverCarryIt() {
        Desk d = desk();
        UUID id = UUID.fromString((String) post(d, card(phone())).getBody().get("id"));
        assertThat(exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(d.token(), Map.of("dob", "1986-07-14")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/patients/" + id, HttpMethod.GET, authed(d.token()), Map.class).getBody()).doesNotContainKey("dobYearOnly");

        ResponseEntity<Map> desk = post(d, Map.of("name", "Desk Person", "phone", phone(), "dob", "1990-02-03", "gender", "male"));
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inTenantTx(d.t().id(), () -> jdbc.queryForObject("SELECT registration_source FROM patients WHERE id = ?", String.class,
                UUID.fromString((String) desk.getBody().get("id"))))).isEqualTo("desk");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMobileAlreadyOnFile_isFoundByTheLiveLookup_blocksTheSaveWith409_andNewPersonSavesIt() {
        Desk d = desk(patients(true));
        String phone = phone();
        assertThat(post(d, Map.of("name", "Existing Person", "phone", phone, "dob", "1971-05-09", "gender", "male")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // what the form asks while the mobile is typed: name and date of birth of whoever has that number
        Map<?, ?> page = exchange("/v1/patients?q=" + phone.substring(3) + "&limit=5", HttpMethod.GET, authed(d.token()), Map.class).getBody();
        Map<String, Object> hit = ((List<Map<String, Object>>) page.get("data")).get(0);
        assertThat(hit).containsEntry("name", "Existing Person").containsEntry("dob", "1971-05-09").containsEntry("matchedOn", "phone");

        ResponseEntity<Map> blocked = post(d, card(phone));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((List<Map<String, Object>>) blocked.getBody().get("candidates")).get(0)).containsEntry("name", "Existing Person");
        assertThat(patients(d)).as("'same person': nothing new is written").isEqualTo(1);

        Map<String, Object> newPerson = card(phone); // household rule: a relative on the same number, confirmed by staff
        newPerson.put("confirmedNotDuplicate", true);
        assertThat(post(d, newPerson).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(patients(d)).isEqualTo(2);
    }

    @Test
    void demoOnlyClinic_readsDemoOnly_andCannotSaveAPaperCard_evenMarkedSynthetic() {
        Desk demo = desk(patients(true));
        jdbc.update("UPDATE tenants SET data_gate = 'demo_only' WHERE id = ?", demo.t().id());
        Desk live = desk(patients(true)); // another clinic's gate is its own

        assertThat(exchange("/v1/patients/data-gate", HttpMethod.GET, authed(demo.token()), Map.class).getBody()).containsEntry("dataGate", "demo_only");
        assertThat(exchange("/v1/patients/data-gate", HttpMethod.GET, authed(live.token()), Map.class).getBody()).containsEntry("dataGate", "live");

        ResponseEntity<Map> refused = post(demo, card(phone()));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(refused.getBody().get("type"))).endsWith("DPA_REQUIRED");
        Map<String, Object> synthetic = card(phone());
        synthetic.put("synthetic", true);
        assertThat(post(demo, synthetic).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patients(demo)).isZero();
    }

    @Test
    void withoutPatientsCreate_neitherTheGateNorTheSaveAnswers() {
        Desk viewer = desk(patients(false));
        assertThat(exchange("/v1/patients/data-gate", HttpMethod.GET, authed(viewer.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(viewer, card(phone())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patients(viewer)).isZero();
    }

    @Test
    void cardRules_yearNeeded_possibleMinorRefused_noDateOnACard_andTheDeskStillNeedsADate() {
        Desk d = desk();
        Map<String, Object> noYear = card(phone());
        noYear.remove("yearOfBirth");
        assertThat(fieldError(post(d, noYear))).isEqualTo("yearOfBirth");

        for (int year : new int[]{1899, THIS_YEAR + 1, THIS_YEAR - 18, THIS_YEAR - 3}) { // out of range, or could still be under 18
            Map<String, Object> b = card(phone());
            b.put("yearOfBirth", year);
            assertThat(fieldError(post(d, b))).as("year %s", year).isEqualTo("yearOfBirth");
        }
        Map<String, Object> adult = card(phone());
        adult.put("yearOfBirth", THIS_YEAR - 19);
        assertThat(post(d, adult).getStatusCode()).as("19 years back is an adult whatever the month").isEqualTo(HttpStatus.CREATED);

        Map<String, Object> withDate = card(phone());
        withDate.put("dob", "1980-01-01");
        assertThat(fieldError(post(d, withDate))).isEqualTo("dob");

        assertThat(fieldError(post(d, Map.of("name", "No Date", "phone", phone(), "gender", "male")))).as("desk registration").isEqualTo("dob");
        assertThat(fieldError(post(d, Map.of("name", "Year Only", "phone", phone(), "gender", "male", "yearOfBirth", 1980)))).isEqualTo("dob");

        Map<String, Object> otherSource = card(phone());
        otherSource.put("source", "whatsapp"); // only the chat path may claim that origin
        assertThat(fieldError(post(d, otherSource))).isEqualTo("source");
        Map<String, Object> longNote = card(phone());
        longNote.put("note", "x".repeat(501));
        assertThat(fieldError(post(d, longNote))).isEqualTo("note");
        assertThat(patients(d)).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeySavesTheCardOnce() {
        Desk d = desk(patients(true));
        Map<String, Object> body = card(phone());
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = exchange("/v1/patients", HttpMethod.POST, authedJsonBodyWithHeader(d.token(), "Idempotency-Key", key, body), Map.class);
        ResponseEntity<Map> again = exchange("/v1/patients", HttpMethod.POST, authedJsonBodyWithHeader(d.token(), "Idempotency-Key", key, body), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(patients(d)).isEqualTo(1);
    }
}
