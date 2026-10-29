package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-017 without Valkey (the live-compute path): ranges, positions, delay, permission, tenant isolation, wait-estimate. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Acc017EtaApiTest extends ApiTestBase {

    @Autowired ClinicClock clock;

    private SeededTenant tenant;
    private SeededStaff doctor;
    private String token;

    private void setUp() {
        tenant = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "e" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        token = loginAndGetAccessToken(doctor);
    }

    static String uniq(String n) { // distinct names: similar ones trip the duplicate-patient check
        StringBuilder b = new StringBuilder("Q");
        for (int i = 0; i < 8; i++) b.append((char) ('a' + (int) (Math.random() * 26)));
        return b.toString();
    }

    private String checkIn(String name) {
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", uniq(name), "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0", "dob", (1930 + (int) (Math.random() * 60)) + "-0" + (1 + (int) (Math.random() * 9)) + "-1" + (int) (Math.random() * 9), "gender", "male")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", doctor.id().toString())), Map.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) c.getBody().get("id");
    }

    private Map eta(String tok, UUID doc) {
        ResponseEntity<Map> r = exchange("/v1/queue/eta?doctorId=" + doc, HttpMethod.GET, authed(tok), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void aCheckedInPatientHasAPositionAndARangeOfAtLeastFiveMinutes() {
        setUp();
        String first = checkIn("Eta One");
        String second = checkIn("Eta Two");
        List<Map<String, Object>> items = (List<Map<String, Object>>) eta(token, doctor.id()).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("queueEntryId")).isEqualTo(first);
        assertThat(items.get(1).get("queueEntryId")).isEqualTo(second);
        assertThat(items.get(0).get("position")).isEqualTo(1);
        assertThat(items.get(1).get("position")).isEqualTo(2);
        for (Map<String, Object> i : items) {
            assertThat((String) i.get("rangeLabel")).matches("\\d\\d:\\d\\d-\\d\\d:\\d\\d");
            Duration width = Duration.between(Instant.parse((String) i.get("p50At")), Instant.parse((String) i.get("p80At")));
            assertThat(width.toMinutes()).isGreaterThanOrEqualTo(5);
        }
        assertThat(Instant.parse((String) items.get(1).get("p50At"))).isAfter(Instant.parse((String) items.get(0).get("p50At")));
    }

    @Test
    void aThirtyMinuteDelayMovesEveryWaitingPatientLaterByAtLeastThirtyMinutes() {
        setUp();
        checkIn("Eta A");
        checkIn("Eta B");
        List<Map<String, Object>> before = (List<Map<String, Object>>) eta(token, doctor.id()).get("items");
        assertThat(exchange("/v1/doctors/" + doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 30, "reason", "r")), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> after = (List<Map<String, Object>>) eta(token, doctor.id()).get("items");
        for (int i = 0; i < before.size(); i++) {
            long moved = Duration.between(Instant.parse((String) before.get(i).get("p50At")), Instant.parse((String) after.get(i).get("p50At"))).toMinutes();
            assertThat(moved).isGreaterThanOrEqualTo(30);
        }
    }

    @Test
    void waitEstimateReadsTheEngineAndSaysItIsNotYetBasedOnLearnedHistory() {
        setUp();
        checkIn("W1");
        checkIn("W2");
        ResponseEntity<Map> r = exchange("/v1/queue/wait-estimate?doctorId=" + doctor.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("patientsAhead")).isEqualTo(2);
        assertThat(((Number) r.getBody().get("estimatedMinutes")).intValue()).isBetween(25, 40); // two ~15 min consults, on the 5 min grid
        assertThat(r.getBody().get("basedOnHistory")).isEqualTo(false);
    }

    @Test
    void anEmptyDayHasNoItemsAndAViewerWithoutQueueViewIsRefused() {
        setUp();
        assertThat((List) eta(token, doctor.id()).get("items")).isEmpty();
        UUID none = seedRole(tenant.id(), "None", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff nobody = seedStaff(tenant, none, "n" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        assertThat(exchange("/v1/queue/eta?doctorId=" + doctor.id(), HttpMethod.GET, authed(loginAndGetAccessToken(nobody)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherClinicNeverSeesThisClinicsQueue() {
        setUp();
        checkIn("Mine");
        SeededTenant other = seedTenant();
        SeededStaff owner = seedStaff(other, seedFullAccessRole(other.id()), "o" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        assertThat((List) eta(loginAndGetAccessToken(owner), doctor.id()).get("items")).isEmpty();
    }
}
