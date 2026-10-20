package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-005. Written to the TimelineApiTest conventions; NOT executed (no Docker/Postgres in this environment). */
class EncounterListApiTest extends ApiTestBase {

    // CLN-005 via CLN-006: signing/amending a note needs a registered clinician; every staff member seeded here is one.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    @Test
    void listsOnlyTheCallersOwnConsultedVisitsWithNoteAndRxState() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "enc1@a.com", "+919800040001", false);
        SeededStaff other = seedStaff(tenant, roleId, "enc2@a.com", "+919800040002", false);
        String token = loginAndGetAccessToken(doc);
        String otherToken = loginAndGetAccessToken(other);
        String patientId = registerPatient(token, "E1", "+919999980001");

        String signed = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/notes/" + signed, HttpMethod.PATCH, authedJsonBody(token, Map.of("diagnosis", "x")), Map.class);
        exchange("/v1/clinical/notes/" + signed + "/sign", HttpMethod.POST, authed(token), Map.class);
        moveTo(token, signed, "waiting", "vitals_pending", "vitals_done", "in_consult");
        String unsigned = checkIn(token, patientId, doc.id());
        moveTo(token, unsigned, "waiting", "vitals_pending", "vitals_done", "in_consult");
        checkIn(token, patientId, doc.id()); // still waiting: never listed

        ResponseEntity<Map> all = exchange("/v1/clinical/encounters", HttpMethod.GET, authed(token), Map.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) all.getBody().get("data")).hasSize(2);

        ResponseEntity<Map> uns = exchange("/v1/clinical/encounters?state=unsigned", HttpMethod.GET, authed(token), Map.class);
        List<?> data = (List<?>) uns.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(((Map<?, ?>) data.get(0)).get("queueEntryId")).isEqualTo(unsigned);
        assertThat(((Map<?, ?>) data.get(0)).get("noteState")).isEqualTo("none");

        ResponseEntity<Map> theirs = exchange("/v1/clinical/encounters", HttpMethod.GET, authed(otherToken), Map.class);
        assertThat((List<?>) theirs.getBody().get("data")).isEmpty();
    }

    @Test
    void rejectsAnUnknownStateAndAnInvertedRange() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "enc3@a.com", "+919800040003", false);
        String token = loginAndGetAccessToken(doc);

        assertThat(exchange("/v1/clinical/encounters?state=bogus", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/clinical/encounters?from=2026-02-02&to=2026-01-01", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // M3 (written, NOT executed: no Docker/Postgres in the authoring environment).
    @Test
    void cursorPagingWalksEveryConsultedVisitOnceAndFiltersByDateStateAndCounts() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "enc4@a.com", "+919800040004", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "E4", "+919999980004");

        List<String> visits = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String v = checkIn(token, patientId, doc.id());
            moveTo(token, v, "waiting", "vitals_pending", "vitals_done", "in_consult");
            visits.add(v);
        }
        exchange("/v1/clinical/prescriptions/" + visits.get(0), HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg", "frequency", "BD", "duration", "3 days")))), Map.class);
        exchange("/v1/clinical/notes/" + visits.get(1), HttpMethod.PATCH, authedJsonBody(token, Map.of("diagnosis", "x")), Map.class);
        exchange("/v1/clinical/notes/" + visits.get(1) + "/sign", HttpMethod.POST, authed(token), Map.class);

        // limit=1 walk: three pages, no repeats, final page has no cursor
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 5; guard++) {
            ResponseEntity<Map> page = exchange("/v1/clinical/encounters?limit=1" + (cursor == null ? "" : "&cursor=" + cursor),
                    HttpMethod.GET, authed(token), Map.class);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<?> data = (List<?>) page.getBody().get("data");
            assertThat(data).hasSizeLessThanOrEqualTo(1);
            data.forEach(row -> seen.add((String) ((Map<?, ?>) row).get("queueEntryId")));
            cursor = (String) ((Map<?, ?>) page.getBody().get("page")).get("nextCursor");
            if (cursor == null) {
                break;
            }
        }
        assertThat(cursor).isNull();
        assertThat(seen).hasSize(3).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(visits);

        // from/to filter on the clinic day: a wide window keeps all, a future window drops all
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ResponseEntity<Map> wide = exchange("/v1/clinical/encounters?from=" + today.minusDays(1) + "&to=" + today.plusDays(1),
                HttpMethod.GET, authed(token), Map.class);
        assertThat((List<?>) wide.getBody().get("data")).hasSize(3);
        ResponseEntity<Map> future = exchange("/v1/clinical/encounters?from=" + today.plusDays(3) + "&to=" + today.plusDays(4),
                HttpMethod.GET, authed(token), Map.class);
        assertThat((List<?>) future.getBody().get("data")).isEmpty();

        // state filter plus the per-row counts
        ResponseEntity<Map> signed = exchange("/v1/clinical/encounters?state=signed", HttpMethod.GET, authed(token), Map.class);
        List<?> signedRows = (List<?>) signed.getBody().get("data");
        assertThat(signedRows).hasSize(1);
        assertThat(((Map<?, ?>) signedRows.get(0)).get("queueEntryId")).isEqualTo(visits.get(1));
        assertThat(((Map<?, ?>) signedRows.get(0)).get("noteState")).isEqualTo("signed");
        ResponseEntity<Map> unsigned = exchange("/v1/clinical/encounters?state=unsigned", HttpMethod.GET, authed(token), Map.class);
        List<?> unsignedRows = (List<?>) unsigned.getBody().get("data");
        assertThat(unsignedRows).hasSize(2);
        Map<?, ?> withRx = (Map<?, ?>) unsignedRows.stream()
                .filter(r -> visits.get(0).equals(((Map<?, ?>) r).get("queueEntryId"))).findFirst().orElseThrow();
        assertThat(withRx.get("rxStatus")).isEqualTo("draft");
        assertThat(((Number) withRx.get("rxItemCount")).intValue()).isEqualTo(1);
        assertThat(((Number) withRx.get("documentsDraft")).intValue()).isZero();
        assertThat(((Number) withRx.get("amendmentCount")).intValue()).isZero();
    }
}
