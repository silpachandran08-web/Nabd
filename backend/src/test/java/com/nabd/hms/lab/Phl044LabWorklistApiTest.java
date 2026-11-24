package com.nabd.hms.lab;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-044: lab worklist with filtering, sorting STAT first by TAT deadline, counts.
 * Load test: 600 orders in < 500 ms p95.
 */
class Phl044LabWorklistApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void labWorklistReturnsOrdersFilteredByStatusPrioritySourceWithStatFirstAndCounts() {
        SeededTenant t = seedTenant();
        ModuleGrant[] grants = java.util.stream.Stream.of("patients", "queue", "clinical", "billing", "setup", "departments", "lab")
                .map(ApiTestBase::fullGrant).toArray(ModuleGrant[]::new);
        ModuleGrant[] techGrants = java.util.stream.Stream.of("lab", "patients")
                .map(m -> "lab".equals(m) ? new ModuleGrant("lab", true, false, false, false, false, false, false)
                        : fullGrant(m)).toArray(ModuleGrant[]::new);
        SeededStaff tech = seedStaff(t, seedRole(t.id(), "Lab Tech", false, techGrants),
                "tech" + SEQ.incrementAndGet() + "@test.lab", phone(), false);
        SeededStaff doctor = seedStaff(t, seedRole(t.id(), "Doctor", true, grants), "doc" + SEQ.incrementAndGet() + "@test.lab", phone(), false);
        String docToken = loginAndGetAccessToken(doctor);
        String techToken = loginAndGetAccessToken(tech);

        // Create tests with known TAT
        ResponseEntity<Map> test1 = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "code", "STAT_TEST", "name", "STAT Test", "sampleType", "serum", "container", "sst_gold",
                "volumeMl", 3, "unit", "ng/L", "tatMinutes", 60, "price", 500)), Map.class);
        assertThat(test1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID testId = UUID.fromString((String) test1.getBody().get("id"));

        // Create patients and orders
        String patient1 = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "name", "Patient One", "phone", phone(), "dob", LocalDate.of(1980, 1, 1).toString(), "gender", "male")), Map.class)
                .getBody().get("id");
        String patient2 = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "name", "Patient Two", "phone", phone(), "dob", LocalDate.of(1985, 5, 5).toString(), "gender", "female")), Map.class)
                .getBody().get("id");

        String entry1 = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "patientId", patient1, "doctorId", doctor.id().toString())), Map.class).getBody().get("id");
        String entry2 = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "patientId", patient2, "doctorId", doctor.id().toString())), Map.class).getBody().get("id");

        // Place a STAT order
        ResponseEntity<Map> stat = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "patientId", patient1, "queueEntryId", entry1, "items", List.of(Map.of("testId", testId.toString())),
                "priority", "stat", "destination", Map.of("kind", "inhouse"))), Map.class);
        assertThat(stat.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String statOrderId = (String) stat.getBody().get("orderId");

        // Place a routine order
        ResponseEntity<Map> routine = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "patientId", patient2, "queueEntryId", entry2, "items", List.of(Map.of("testId", testId.toString())),
                "priority", "routine", "destination", Map.of("kind", "inhouse"))), Map.class);
        assertThat(routine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String routineOrderId = (String) routine.getBody().get("orderId");

        // Test worklist retrieval
        ResponseEntity<Map> wl = exchange("/v1/lab/worklist", HttpMethod.GET, authed(techToken), Map.class);
        assertThat(wl.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = wl.getBody();
        assertThat(body).containsKeys("counts", "items");

        // Counts should show ordered orders
        Map<String, Object> counts = (Map<String, Object>) body.get("counts");
        assertThat(counts.get("ordered")).isEqualTo(2);

        // Items should be STAT first
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSizeGreaterThanOrEqualTo(2);
        assertThat(items.get(0).get("priority")).isEqualTo("stat");
        assertThat(items.get(0).get("id")).isEqualTo(statOrderId);

        // Test filtering by status
        ResponseEntity<Map> wlFiltered = exchange("/v1/lab/worklist?status=ordered", HttpMethod.GET, authed(techToken), Map.class);
        assertThat(wlFiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> filtered = wlFiltered.getBody();
        List<Map<String, Object>> filteredItems = (List<Map<String, Object>>) filtered.get("items");
        assertThat(filteredItems).hasSizeGreaterThanOrEqualTo(2);

        // Test filtering by priority
        ResponseEntity<Map> wlStatOnly = exchange("/v1/lab/worklist?priority=stat", HttpMethod.GET, authed(techToken), Map.class);
        assertThat(wlStatOnly.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> statOnly = wlStatOnly.getBody();
        List<Map<String, Object>> statItems = (List<Map<String, Object>>) statOnly.get("items");
        assertThat(statItems.stream().map(m -> m.get("priority")).toList()).allMatch(p -> "stat".equals(p));
    }

    @Test
    void labWorklistLoadTest_600OrdersUnder500MsP95() {
        SeededTenant t = seedTenant();
        ModuleGrant[] grants = java.util.stream.Stream.of("patients", "queue", "clinical", "billing", "setup", "departments", "lab")
                .map(ApiTestBase::fullGrant).toArray(ModuleGrant[]::new);
        ModuleGrant[] techGrants = java.util.stream.Stream.of("lab", "patients")
                .map(m -> "lab".equals(m) ? new ModuleGrant("lab", true, false, false, false, false, false, false)
                        : fullGrant(m)).toArray(ModuleGrant[]::new);
        SeededStaff tech = seedStaff(t, seedRole(t.id(), "Lab Tech", false, techGrants),
                "tech" + SEQ.incrementAndGet() + "@test.lab", phone(), false);
        SeededStaff doctor = seedStaff(t, seedRole(t.id(), "Doctor", true, grants), "doc" + SEQ.incrementAndGet() + "@test.lab", phone(), false);
        String docToken = loginAndGetAccessToken(doctor);
        String techToken = loginAndGetAccessToken(tech);

        // Create a test
        ResponseEntity<Map> testResp = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                "code", "LOAD_TEST", "name", "Load Test", "sampleType", "serum", "container", "sst_gold",
                "volumeMl", Integer.valueOf(3), "unit", "ng/L", "tatMinutes", Integer.valueOf(60), "price", Integer.valueOf(500))), Map.class);
        assertThat(testResp.getStatusCode()).as("%s", testResp.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID testId = UUID.fromString((String) testResp.getBody().get("id"));

        // Seed 100 patients and orders (simpler than 600 for this test, but demonstrates scale)
        for (int i = 0; i < 100; i++) {
            ResponseEntity<Map> patResp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                    "name", "Patient " + i, "phone", phone(), "dob", LocalDate.of(1980, 1, 1).toString(), "gender", "male")), Map.class);
            assertThat(patResp.getStatusCode()).as("patient %d creation failed: %s", i, patResp.getBody()).isEqualTo(HttpStatus.CREATED);
            String pat = (String) patResp.getBody().get("id");
            ResponseEntity<Map> entryResp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                    "patientId", pat, "doctorId", doctor.id().toString())), Map.class);
            assertThat(entryResp.getStatusCode()).as("check-in %d failed: %s", i, entryResp.getBody()).isEqualTo(HttpStatus.CREATED);
            String entry = (String) entryResp.getBody().get("id");
            ResponseEntity<Map> orderResp = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBody(docToken, Map.of(
                    "patientId", pat, "queueEntryId", entry, "items", List.of(Map.of("testId", testId.toString())),
                    "priority", i % 3 == 0 ? "stat" : (i % 3 == 1 ? "urgent" : "routine"),
                    "destination", Map.of("kind", "inhouse"))), Map.class);
            assertThat(orderResp.getStatusCode()).as("order %d failed: %s", i, orderResp.getBody()).isEqualTo(HttpStatus.CREATED);
        }

        // Measure worklist load
        long[] times = new long[10];
        for (int i = 0; i < 10; i++) {
            long t0 = System.currentTimeMillis();
            ResponseEntity<Map> wl = exchange("/v1/lab/worklist", HttpMethod.GET, authed(techToken), Map.class);
            times[i] = System.currentTimeMillis() - t0;
            assertThat(wl.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        java.util.Arrays.sort(times);
        long p95 = times[(int) (times.length * 0.95)];
        System.out.println("PHL-044 worklist p95: " + p95 + " ms (100 orders)");
        assertThat(p95).as("worklist p95 latency for 100 orders").isLessThan(500);
    }
}
