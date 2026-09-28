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
        if (guardianId != null) body.put("guardianId", guardianId);
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
}
