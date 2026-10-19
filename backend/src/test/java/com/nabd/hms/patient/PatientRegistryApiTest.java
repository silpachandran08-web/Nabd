package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Patient Registry: every flag from real data, the per-filter counts, search and the archived rule. */
class PatientRegistryApiTest extends ApiTestBase {

    private String register(String token, String name, String phone, String dob, String guardianId) {
        return register(token, name, phone, dob, guardianId, false);
    }

    /** confirmed = the Register dialog's second step, after staff reviewed the duplicate candidates. */
    private String register(String token, String name, String phone, String dob, String guardianId, boolean confirmed) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "phone", phone, "dob", dob, "gender", "female"));
        if (guardianId != null) { body.put("guardianId", guardianId); body.put("guardianConsent", Map.of("confirmed", true, "channel", "in_person")); }
        if (confirmed) body.put("confirmedNotDuplicate", true);
        var resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(resp.getStatusCode()).as(name).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registryFlagsCountsSearchAndArchive() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "reg1@a.com", "+919800067001", false);
        String token = loginAndGetAccessToken(owner);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // ACC-004: bookings must fall inside working hours; tomorrow 10:00 UTC
        exchange("/v1/doctors/" + owner.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", today.plusDays(1).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);

        String inQueue = register(token, "Ananya Rao", "+919811177001", "1990-01-01", null);
        Map<String, Object> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", inQueue, "doctorId", owner.id().toString())), Map.class).getBody();
        String booked = register(token, "Suresh Pillai", "+919811177002", "1980-02-02", null);
        exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", booked, "doctorId", owner.id().toString(),
                "startTime", today.plusDays(1).atTime(10, 0).toInstant(ZoneOffset.UTC).toString())), Map.class);
        String followUp = register(token, "Rakesh Menon", "+919811177003", "1966-03-03", null);
        String allergic = register(token, "Arjun Nair", "+919811177004", "1970-04-04", null);
        String owes = register(token, "Fatima Sheikh", "+919811177005", "1996-05-05", null);
        // same phone as Ananya: blocked with candidates until staff confirm it's someone else
        assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Priya Kumar",
                "phone", "+919811177001", "dob", "1975-06-06", "gender", "female")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String dupe = register(token, "Priya Kumar", "+919811177001", "1975-06-06", null, true);
        String merged = register(token, "Old Record", "+919811177007", "1960-07-07", null);
        String guardian = register(token, "Leela Das", "+919811177008", "1980-08-08", null);
        String child = register(token, "Mira Das", "+919811177008", today.minusYears(8).toString(), guardian, true); // family shares the number
        inTenantTx(tenant.id(), () -> {
            jdbc.update("INSERT INTO chronic_conditions (tenant_id, patient_id, condition, review_due_date, recorded_by) VALUES (?,?::uuid,'Diabetic',?,?)",
                    tenant.id(), followUp, java.sql.Date.valueOf(today.minusDays(1)), owner.id());
            jdbc.update("INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, recorded_by) VALUES (?,?::uuid,'Penicillin','severe',?)",
                    tenant.id(), allergic, owner.id());
            jdbc.update("INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by) VALUES (?,?::uuid,500,0,500,?)",
                    tenant.id(), owes, owner.id());
            jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ?::uuid WHERE id = ?::uuid", owes, merged);
        });

        Map<String, Object> all = exchange("/v1/patients/registry", HttpMethod.GET, authed(token), Map.class).getBody();
        Map<String, Object> counts = (Map<String, Object>) all.get("counts");
        assertThat(counts).containsEntry("all", 8).containsEntry("archived", 1).containsEntry("recent", 1)
                .containsEntry("followup", 1).containsEntry("balance", 1).containsEntry("package", 0).containsEntry("duplicate", 2);
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        ((List<Map<String, Object>>) all.get("rows")).forEach(r -> byName.put((String) r.get("name"), r));
        assertThat(byName).doesNotContainKey("Old Record"); // archived is out of "all"
        assertThat(byName.get("Ananya Rao")).containsEntry("queueToken", checkIn.get("tokenNumber")).containsEntry("lastVisit", today.toString());
        assertThat(byName.get("Suresh Pillai")).containsEntry("nextAppointmentToday", false).containsEntry("nextDoctorName", "Test Staff");
        assertThat(byName.get("Rakesh Menon")).containsEntry("condition", "Diabetic").containsEntry("followUpDue", true);
        assertThat(byName.get("Arjun Nair")).containsEntry("allergy", "Penicillin");
        assertThat(byName.get("Priya Kumar")).containsEntry("duplicate", true);
        assertThat(byName.get("Leela Das")).containsEntry("duplicate", false); // guardian + child share a phone: not a duplicate
        assertThat(byName.get("Mira Das")).containsEntry("minor", true).containsEntry("duplicate", false);

        assertThat((List<?>) exchange("/v1/patients/registry?q=T-" + checkIn.get("tokenNumber"), HttpMethod.GET, authed(token), Map.class)
                .getBody().get("rows")).hasSize(1);
        assertThat((List<?>) exchange("/v1/patients/registry?q=77004", HttpMethod.GET, authed(token), Map.class).getBody().get("rows"))
                .singleElement().satisfies(r -> assertThat(((Map<?, ?>) r).get("id")).isEqualTo(allergic));
        assertThat((List<?>) exchange("/v1/patients/registry?filter=archived", HttpMethod.GET, authed(token), Map.class).getBody().get("rows"))
                .singleElement().satisfies(r -> assertThat(((Map<?, ?>) r).get("id")).isEqualTo(merged));
        assertThat(exchange("/v1/patients/registry?filter=bogus", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // the same number typed another way is still recognised, and phones are stored in one format
        assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Someone Else",
                "phone", "98111 77004", "dob", "2001-01-01", "gender", "male")), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String spaced = register(token, "Nisha Verma", "98111 77099", "1993-09-09", null);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT phone FROM patients WHERE id = ?::uuid", String.class, spaced)))
                .isEqualTo("+919811177099");
        assertThat(child).isNotNull();
        assertThat(dupe).isNotNull();
    }

    /** REC-040: limit + opaque cursor page the matching rows; a call without paging params is unchanged. */
    @Test
    @SuppressWarnings("unchecked")
    void registryPagesWithLimitAndCursorAndStaysInTenant() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "reg2@a.com", "+919800067002", false);
        String token = loginAndGetAccessToken(owner);
        java.util.Set<String> ids = new java.util.HashSet<>();
        String[] names = {"Pager Aamir", "Pager Bina", "Pager Chetan", "Pager Divya", "Pager Eshan"};
        for (int i = 0; i < names.length; i++) {
            ids.add(register(token, names[i], "+91981117710" + i, (1950 + i * 7) + "-0" + (i + 1) + "-1" + i, null));
        }

        // no paging params: today's call shape still returns every row, plus the new fields
        Map<String, Object> unpaged = exchange("/v1/patients/registry", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat((List<?>) unpaged.get("rows")).hasSize(5);
        assertThat(unpaged).containsEntry("total", 5).containsEntry("matchedTotal", 5).containsEntry("hasMore", false);
        assertThat((Map<String, Object>) unpaged.get("page")).containsEntry("nextCursor", null).containsEntry("limit", 200);

        // limit=2 walks 2 + 2 + 1, each patient exactly once, in name order
        java.util.List<String> seen = new java.util.ArrayList<>();
        java.util.List<String> seenNames = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/v1/patients/registry?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            Map<String, Object> page = exchange(path, HttpMethod.GET, authed(token), Map.class).getBody();
            List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
            assertThat(rows.size()).isLessThanOrEqualTo(2);
            assertThat(page).containsEntry("matchedTotal", 5);
            rows.forEach(r -> { seen.add((String) r.get("id")); seenNames.add((String) r.get("name")); });
            cursor = (String) ((Map<String, Object>) page.get("page")).get("nextCursor");
            assertThat(page.get("hasMore")).isEqualTo(cursor != null);
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(seenNames).containsExactly(names);

        // paging composes with search: q narrows matchedTotal, then pages over that
        Map<String, Object> searched = exchange("/v1/patients/registry?q=pager&limit=4", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(searched).containsEntry("matchedTotal", 5).containsEntry("hasMore", true);
        assertThat((List<?>) searched.get("rows")).hasSize(4);
        Map<String, Object> one = exchange("/v1/patients/registry?q=Divya&limit=4", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(one).containsEntry("matchedTotal", 1).containsEntry("hasMore", false);

        // PLT-027: an out-of-range limit is a 400 (was clamped), a foreign/garbled cursor is a 400
        assertThat(exchange("/v1/patients/registry?limit=0", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/patients/registry?cursor=not-a-cursor", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        String encounterStyleCursor = new com.nabd.hms.common.Cursor(java.time.Instant.now(), UUID.randomUUID()).encode();
        assertThat(exchange("/v1/patients/registry?cursor=" + encounterStyleCursor, HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // tenant isolation: another clinic's registry (and its paging) never contains these patients
        SeededTenant other = seedTenant();
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "reg3@a.com", "+919800067003", false);
        String otherToken = loginAndGetAccessToken(otherOwner);
        String otherPatient = register(otherToken, "Pager Other", "+919811177199", "1999-09-09", null);
        Map<String, Object> otherPage = exchange("/v1/patients/registry?limit=1", HttpMethod.GET, authed(otherToken), Map.class).getBody();
        assertThat(otherPage).containsEntry("total", 1).containsEntry("matchedTotal", 1).containsEntry("hasMore", false);
        assertThat(((List<Map<String, Object>>) otherPage.get("rows")).stream().map(r -> r.get("id")).toList())
                .containsExactly(otherPatient);
        Map<String, Object> otherSearch = exchange("/v1/patients/registry?q=Aamir", HttpMethod.GET, authed(otherToken), Map.class).getBody();
        assertThat(otherSearch).containsEntry("matchedTotal", 0);
        assertThat((List<?>) otherSearch.get("rows")).isEmpty();
    }
}
