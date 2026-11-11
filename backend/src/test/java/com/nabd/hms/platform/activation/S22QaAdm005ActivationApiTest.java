package com.nabd.hms.platform.activation;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S22 QA (t1), ADM-005: who may read the funnel and stuck list (operator onboarding:view only), invalid query input never gives a
 * 500, and neither response carries a clinic name, owner name or phone (ids, step names, counts and times only).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaAdm005ActivationApiTest extends ApiTestBase {

    @Autowired OutboxDispatcher dispatcher;

    private String operator(String role) {
        return platformLoginAndGetAccessToken(seedOperator("qa22-" + role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
    }

    @Test
    void c01_clinicTokensAndOperatorsWithoutOnboardingViewAreRefused() {
        SeededTenant t = seedTenant();
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa22-own-" + t.slug() + "@act.test", "+919811122233", false));
        for (String p : List.of("/v1/platform/activation/funnel", "/v1/platform/activation/stuck")) {
            assertThat(exchange(p, HttpMethod.GET, authed(owner), Map.class).getStatusCode().value()).as("clinic owner " + p).isIn(401, 403);
            for (String role : List.of("support_engineer", "commercial")) {
                assertThat(exchange(p, HttpMethod.GET, authed(operator(role)), Map.class).getStatusCode()).as(role + " " + p).isEqualTo(HttpStatus.FORBIDDEN);
            }
            assertThat(exchange(p, HttpMethod.GET, null, Map.class).getStatusCode()).as("anonymous " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void c02_invalidQueryInputIs400_neverA500() {
        String op = operator("implementation");
        for (String q : List.of("/funnel?from=garbage", "/funnel?to=2026-13-40", "/funnel?from=2026-06-01&to=2026-01-01",
                "/stuck?hours=0", "/stuck?hours=abc", "/stuck?hours=99999", "/stuck?cursor=not-base64!!", "/stuck?cursor=Zm9v", "/stuck?limit=abc")) {
            ResponseEntity<String> r = exchange("/v1/platform/activation" + q, HttpMethod.GET, authed(op), String.class);
            assertThat(r.getStatusCode()).as(q + " -> " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(exchange("/v1/platform/activation/stuck?limit=-5", HttpMethod.GET, authed(op), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/activation/funnel?market=%27%3B--&specialty=x", HttpMethod.GET, authed(op), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void c03_funnelAndStuckCarryNoClinicNameOwnerNameOrPhone() {
        SeededTenant t = seedTenant();
        String clinicName = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, t.id());
        String phone = String.format("+9198%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000));
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa22-phi-" + t.slug() + "@act.test", phone, false));
        assertThat(exchange("/v1/setup/checklist", HttpMethod.GET, authed(owner), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        inTenantTx(t.id(), () -> jdbc.update("UPDATE master.activation_steps SET first_seen_at = now() - interval '49 hours' WHERE tenant_id = ?", t.id()));

        String op = operator("implementation");
        String today = LocalDate.now().plusDays(1).toString();
        ResponseEntity<String> funnel = exchange("/v1/platform/activation/funnel?from=" + LocalDate.now().minusWeeks(2) + "&to=" + today, HttpMethod.GET, authed(op), String.class);
        ResponseEntity<Map> stuck = exchange("/v1/platform/activation/stuck?hours=48&limit=100", HttpMethod.GET, authed(op), Map.class);
        assertThat(funnel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stuck.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = funnel.getBody() + stuck.getBody();
        assertThat(body).doesNotContain(clinicName).doesNotContain(phone).doesNotContain(phone.substring(3)).doesNotContain("Test Staff").doesNotContain("@act.test");
        List<Map<String, Object>> items = (List<Map<String, Object>>) stuck.getBody().get("items");
        assertThat(items).anySatisfy(i -> assertThat(i).containsEntry("tenantId", t.id().toString()));
        assertThat(items).allSatisfy(i -> assertThat(i.keySet()).containsExactlyInAnyOrder("tenantId", "step", "since", "nudgesSent"));
    }
}
