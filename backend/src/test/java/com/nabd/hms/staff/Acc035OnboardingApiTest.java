package com.nabd.hms.staff;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-035: GET/PUT /v1/me/onboarding: tour completion per account (not per browser), the ten-task competency check, own rows, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc035OnboardingApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);
    private static final List<String> TASKS = List.of("walkin", "checkin", "queue", "requests", "delay", "checkout", "reschedule", "noshow", "search", "offline");

    private final Map<java.util.UUID, java.util.UUID> roles = new java.util.concurrent.ConcurrentHashMap<>();

    private SeededStaff staff(SeededTenant t) {
        return seedStaff(t, roles.computeIfAbsent(t.id(), this::seedFullAccessRole), "a35-" + SEQ.incrementAndGet() + "@a.com", "+9193" + (10000000 + SEQ.get() * 7), false);
    }

    private Map<String, Object> get(String token) {
        ResponseEntity<Map> r = exchange("/v1/me/onboarding", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> put(String token, String key) {
        return exchange("/v1/me/onboarding/" + key, HttpMethod.PUT, authed(token), Map.class);
    }

    @Test
    void ac1_theTourIsDoneOncePerAccount_anyBrowser_andRepeatingIsHarmless() {
        SeededTenant t = seedTenant();
        SeededStaff s = staff(t);
        String token = loginAndGetAccessToken(s);
        assertThat(get(token)).containsEntry("completed", List.of()).containsEntry("competencyPassedAt", null);
        assertThat(put(token, "tour.reception").getStatusCode()).isEqualTo(HttpStatus.OK);
        String first = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT completed_at::text FROM staff_onboarding_progress WHERE tour_key = 'tour.reception'", String.class));
        assertThat(put(token, "tour.reception").getBody().get("completed")).isEqualTo(List.of("tour.reception"));
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT completed_at::text FROM staff_onboarding_progress WHERE tour_key = 'tour.reception'", String.class))).isEqualTo(first);
        String otherBrowser = loginAndGetAccessToken(s); // a second sign-in (another device) sees the same state
        assertThat(get(otherBrowser).get("completed")).isEqualTo(List.of("tour.reception"));

        for (String bad : List.of("tour.pharmacist", "task.unknown", "competency.reception", "tour.reception.x")) {
            ResponseEntity<Map> r = put(token, bad);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(422);
            assertThat(r.getBody().get("code")).isEqualTo("UNKNOWN_ONBOARDING_KEY");
        }
        assertThat(exchange("/v1/me/onboarding", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac4_competencyPassesOnTheTenthTask_onceAndAudited_ownRowsOnly_tenantIsolated() {
        SeededTenant t = seedTenant();
        SeededStaff s = staff(t);
        String token = loginAndGetAccessToken(s);
        for (String task : TASKS.subList(0, 9)) put(token, "task." + task);
        assertThat(get(token).get("competencyPassedAt")).isNull();
        Map<String, Object> passed = put(token, "task.offline").getBody();
        assertThat(passed.get("competencyPassedAt")).isNotNull();
        assertThat((List<String>) passed.get("completed")).hasSize(10);
        assertThat(put(token, "task.walkin").getBody().get("competencyPassedAt")).isEqualTo(passed.get("competencyPassedAt"));
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'staff.competency_passed' AND actor_id = ?", Integer.class, s.id()))).isEqualTo(1);

        String colleague = loginAndGetAccessToken(staff(t));
        assertThat(get(colleague)).containsEntry("completed", List.of()).containsEntry("competencyPassedAt", null);
        SeededTenant b = seedTenant();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_onboarding_progress", Integer.class))).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM staff_onboarding_progress", Integer.class))).isEqualTo(11);
    }
}
