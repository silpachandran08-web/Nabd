package com.nabd.hms.platform.plans;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-034: plan catalogue (modules, limits, currencies, pricing basis), versioning that keeps subscriptions pinned. */
class Adm034PlanCatalogueApiTest extends ApiTestBase {

    private static final List<String> CLINIC_PLUS = List.of("queue_eta", "whatsapp_booking", "emr_erx", "billing", "packages", "branded_site", "hr");

    private String billing() {
        return platformLoginAndGetAccessToken(seedOperator("bl-" + UUID.randomUUID() + "@nabd.internal", "billing", false));
    }

    private ResponseEntity<Map> create(String token, String code, String currency, String basis, List<String> modules, String market) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("code", code, "name", "Clinic Plus", "monthlyPriceCents", 150000,
                "currency", currency, "active", true, "modules", modules, "limits", Map.of("seats", 15, "branches", 3)));
        if (basis != null) b.put("pricingBasis", basis);
        if (market != null) b.put("market", market);
        return exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(billing(), b), Map.class);
    }

    @Test
    void clinicPlusIsVersionedAndAppearsInThePublicCatalogue() {
        String code = "clinic-plus-" + UUID.randomUUID();
        ResponseEntity<Map> r = create("t", code, "AED", "per_doctor", CLINIC_PLUS, "AE-" + code);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("version")).isEqualTo(1);
        assertThat((List<String>) r.getBody().get("modules")).containsExactlyInAnyOrderElementsOf(CLINIC_PLUS);
        assertThat(r.getBody().get("seatLimit")).isEqualTo(15);
        assertThat(r.getBody().get("branchLimit")).isEqualTo(3);

        // public: no token
        ResponseEntity<List> cat = exchange("/v1/plans/catalogue?market=AE-" + code, HttpMethod.GET, HttpEntity.EMPTY, List.class);
        assertThat(cat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cat.getHeaders().getCacheControl()).contains("public");
        assertThat(cat.getBody()).singleElement().satisfies(o -> {
            Map<?, ?> e = (Map<?, ?>) o;
            assertThat(e.get("code")).isEqualTo(code);
            assertThat(e.get("currency")).isEqualTo("AED");
            assertThat(e.get("pricingBasis")).isEqualTo("per_doctor");
            assertThat(((Map<?, ?>) e.get("limits")).get("branches")).isEqualTo(3);
        });
        // another market does not see it
        assertThat(exchange("/v1/plans/catalogue?market=ZZ-" + code, HttpMethod.GET, HttpEntity.EMPTY, List.class).getBody()).isEmpty();
    }

    @Test
    void currenciesAndModuleCodesAreValidated() {
        assertThat(create("t", "q-" + UUID.randomUUID(), "QAR", null, List.of("billing"), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create("t", "u-" + UUID.randomUUID(), "USD", null, List.of("billing"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> bad = create("t", "m-" + UUID.randomUUID(), "INR", null, List.of("billing", "teleport"), null);
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(bad.getBody().get("title")).isEqualTo("UNKNOWN_MODULE");
        String dup = "d-" + UUID.randomUUID();
        create("t", dup, "INR", null, List.of("billing"), null);
        ResponseEntity<Map> again = create("t", dup, "INR", null, List.of("billing"), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("title")).isEqualTo("CODE_EXISTS");
    }

    @Test
    void editingModulesOfAPlanInUseCreatesANewVersionAndSubscriptionsStayPinned() {
        String op = billing();
        String code = "pin-" + UUID.randomUUID();
        Map<?, ?> plan = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of("code", code, "name", "Pin",
                "monthlyPriceCents", 100000, "currency", "INR", "active", true, "modules", List.of("billing"),
                "limits", Map.of("seats", 5))), Map.class).getBody();
        String id = (String) plan.get("id");
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String clinic = loginAndGetAccessToken(seedStaff(t, role, "o@pin.test", "+919500000071", false));
        exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of("planId", id,
                "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);

        ResponseEntity<Map> edited = exchange("/v1/platform/plans/" + id, HttpMethod.PATCH, authedJsonBody(op, Map.of("code", code,
                "name", "Pin", "monthlyPriceCents", 100000, "currency", "INR", "active", true,
                "modules", List.of("billing", "lab"), "limits", Map.of("seats", 9))), Map.class);
        assertThat(edited.getBody().get("version")).isEqualTo(2);
        assertThat(edited.getBody().get("seatLimit")).isEqualTo(9);

        // the clinic still has version 1: no lab, 5 seats
        assertThat(jdbc.queryForObject("SELECT plan_version FROM master.subscriptions WHERE tenant_id = ?", Integer.class, t.id())).isEqualTo(1);
        Map<?, ?> setup = exchange("/v1/setup/subscription", HttpMethod.GET, authed(clinic), Map.class).getBody();
        assertThat(setup.get("seatsLimit")).isEqualTo(5);
        assertThat((List<Map<String, Object>>) setup.get("modules")).anyMatch(m -> m.get("code").equals("lab") && m.get("enabled").equals(false));

        List<Map<String, Object>> versions = exchange("/v1/platform/plans/" + id + "/versions", HttpMethod.GET, authed(op), List.class).getBody();
        assertThat(versions).extracting(v -> v.get("version")).containsExactly(2, 1);
        assertThat(versions.get(1).get("subscriptions")).isEqualTo(1);

        // an edit that changes nothing commercial does not publish a version
        ResponseEntity<Map> rename = exchange("/v1/platform/plans/" + id, HttpMethod.PATCH, authedJsonBody(op, Map.of("code", code,
                "name", "Pin renamed", "monthlyPriceCents", 100000, "currency", "INR", "active", true)), Map.class);
        assertThat(rename.getBody().get("version")).isEqualTo(2);

        // migrating the subscription moves it to version 2
        exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of("planId", id,
                "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(jdbc.queryForObject("SELECT plan_version FROM master.subscriptions WHERE tenant_id = ?", Integer.class, t.id())).isEqualTo(2);
        assertThat(((Map<?, ?>) exchange("/v1/setup/subscription", HttpMethod.GET, authed(clinic), Map.class).getBody()).get("seatsLimit")).isEqualTo(9);
    }

    @Test
    void perDoctorSubscriptionIsPricedFromActiveCliniciansAndPerClinicIsFlat() {
        String op = billing();
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        seedStaff(t, role, "d1@price.test", "+919500000081", false);
        seedStaff(t, role, "d2@price.test", "+919500000082", false);
        seedStaff(t, role, "n1@price.test", "+919500000083", false);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET is_clinician = true WHERE tenant_id = ? AND email IN ('d1@price.test','d2@price.test')", t.id()));

        String perDoctor = (String) create("t", "pd-" + UUID.randomUUID(), "INR", "per_doctor", List.of("billing"), null).getBody().get("id");
        Map<?, ?> priced = exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of(
                "planId", perDoctor, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class).getBody();
        assertThat(priced.get("mrrCents")).isEqualTo(300000); // 150000 x 2 clinicians

        String flat = (String) create("t", "pc-" + UUID.randomUUID(), "INR", "per_clinic", List.of("billing"), null).getBody().get("id");
        Map<?, ?> flatPriced = exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of(
                "planId", flat, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class).getBody();
        assertThat(flatPriced.get("mrrCents")).isEqualTo(150000);
    }
}
