package com.nabd.hms.platform.entitlements;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-059: seat limit, module gating (writes only), per-clinic and global kill switches, override expiry, /me/entitlements. */
class Plt059EntitlementsApiTest extends ApiTestBase {

    private String operator() {
        return platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    private UUID plan(String token, List<String> modules, int seats) {
        ResponseEntity<Map> r = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(token, Map.of(
                "code", "p-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", seats, "branches", 2))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void subscribe(String token, UUID tenant, UUID plan) {
        ResponseEntity<Map> r = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(token,
                Map.of("planId", plan.toString(), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String clinic(SeededTenant t, String mail, String phone) {
        UUID role = seedFullAccessRole(t.id());
        String token = loginAndGetAccessToken(seedStaff(t, role, mail, phone, false));
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        return token;
    }

    private ResponseEntity<Map> addItem(String clinicToken, String name) {
        return exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(clinicToken, Map.of(
                "name", name, "isRx", false, "hsnCode", "3304", "price", 350.00, "taxRatePercent", 12.00, "stockQty", 5)), Map.class);
    }

    private ResponseEntity<Map> kill(String op, UUID tenant, String code, String state, Object expiresAt) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("state", state, "reason", "bot loop reported"));
        if (expiresAt != null) b.put("expiresAt", expiresAt);
        return exchange("/v1/platform/tenants/" + tenant + "/features/" + code, HttpMethod.PUT, authedJsonBody(op, b), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> modules(String clinicToken) {
        Map<String, Object> body = exchange("/v1/me/entitlements", HttpMethod.GET, authed(clinicToken), Map.class).getBody();
        Map<String, Boolean> out = new java.util.HashMap<>();
        ((List<Map<String, Object>>) body.get("modules")).forEach(m -> out.put((String) m.get("code"), (Boolean) m.get("enabled")));
        return out;
    }

    @Test
    void sixthInviteIsRefusedAtTheSeatLimitAndSuspendedStaffDoNotCount() {
        String op = operator();
        SeededTenant t = seedTenant();
        String owner = clinic(t, "o@seat.test", "+919500000001");
        subscribe(op, t.id(), plan(op, List.of("billing"), 2)); // 2 seats: the owner + one more
        Map<String, Object> invite = Map.of("email", "a@seat.test", "name", "A", "mobilePhone", "+919500000002",
                "roleId", inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT role_id FROM staff WHERE tenant_id = ?", UUID.class, t.id())).toString());

        ResponseEntity<Map> second = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(owner, invite), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> third = new java.util.HashMap<>(invite);
        third.put("email", "b@seat.test");
        third.put("mobilePhone", "+919500000003");
        ResponseEntity<Map> refused = exchange("/v1/staff", HttpMethod.POST, authedJsonBody(owner, third), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("title")).isEqualTo("SEAT_LIMIT_REACHED");
        assertThat(refused.getBody().get("upgradeUrl")).isEqualTo("/owner/plan");

        // suspending the invitee frees the seat
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET status = 'suspended' WHERE tenant_id = ? AND email = 'a@seat.test'", t.id()));
        assertThat(exchange("/v1/staff", HttpMethod.POST, authedJsonBody(owner, third), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void stockWriteNeedsTheModuleButStockReadsStayOpen() {
        String op = operator();
        SeededTenant t = seedTenant();
        String clinic = clinic(t, "o@mod.test", "+919500000011");
        UUID noPharmacy = plan(op, List.of("billing", "lab"), 5);
        subscribe(op, t.id(), noPharmacy);

        ResponseEntity<Map> refused = addItem(clinic, "Paracetamol");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("title")).isEqualTo("MODULE_NOT_IN_PLAN");
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(clinic), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(modules(clinic)).containsEntry("pharmacy_inhouse", false).containsEntry("lab", true);

        // plan change takes effect at once on this replica (cache evicted), no restart
        subscribe(op, t.id(), plan(op, List.of("billing", "pharmacy_inhouse"), 5));
        assertThat(addItem(clinic, "Paracetamol").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(modules(clinic)).containsEntry("pharmacy_inhouse", true);
    }

    @Test
    void tenantWithoutSubscriptionIsUnrestricted() {
        SeededTenant t = seedTenant();
        String clinic = clinic(t, "o@free.test", "+919500000021");
        assertThat(addItem(clinic, "Gauze").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(modules(clinic).values()).doesNotContain(false);
    }

    @Test
    void perClinicKillSwitchStopsWritesAuditedAndOtherClinicsAreUnaffected() {
        String op = operator();
        SeededTenant a = seedTenant(), b = seedTenant();
        String clinicA = clinic(a, "o@ka.test", "+919500000031"), clinicB = clinic(b, "o@kb.test", "+919500000032");

        assertThat(kill(op, a.id(), "whatsapp_bot", "kill", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kill(op, a.id(), "pharmacy_inhouse", "kill", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> me = exchange("/v1/me/entitlements", HttpMethod.GET, authed(clinicA), Map.class).getBody();
        assertThat((List<String>) me.get("killedFeatures")).containsExactly("pharmacy_inhouse", "whatsapp_bot");
        ResponseEntity<Map> down = addItem(clinicA, "X");
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getBody().get("title")).isEqualTo("FEATURE_DISABLED");
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(clinicA), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat((List<String>) exchange("/v1/me/entitlements", HttpMethod.GET, authed(clinicB), Map.class).getBody().get("killedFeatures")).isEmpty();
        assertThat(addItem(clinicB, "X").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Long audited = inTenantTx(a.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'platform_feature.override_set'", Long.class, a.id()));
        assertThat(audited).isEqualTo(2);
        Long events = inTenantTx(a.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'platform.entitlements.changed.v1'", Long.class, a.id()));
        assertThat(events).isEqualTo(2);
    }

    @Test
    void overrideRefusalsAndPermission() {
        String op = operator();
        SeededTenant t = seedTenant();
        assertThat(kill(op, t.id(), "no_such_feature", "kill", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(kill(op, UUID.randomUUID(), "lab", "off", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> noReason = exchange("/v1/platform/tenants/" + t.id() + "/features/lab", HttpMethod.PUT,
                authedJsonBody(op, Map.of("state", "kill", "reason", " ")), Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(kill(op, t.id(), "lab", "kill", Instant.now().minusSeconds(5).toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String billing = platformLoginAndGetAccessToken(seedOperator("b-" + UUID.randomUUID() + "@nabd.internal", "billing", false));
        assertThat(kill(billing, t.id(), "lab", "kill", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anExpiredOverrideFallsBackToThePlanDefault() throws Exception {
        String op = operator();
        SeededTenant t = seedTenant();
        String clinic = clinic(t, "o@exp.test", "+919500000041");
        subscribe(op, t.id(), plan(op, List.of("billing"), 5)); // pharmacy_inhouse is not in the plan
        assertThat(kill(op, t.id(), "pharmacy_inhouse", "on", Instant.now().plusSeconds(3).toString()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(addItem(clinic, "Y").getStatusCode()).isEqualTo(HttpStatus.CREATED); // override on beats the plan

        Thread.sleep(3500);
        assertThat(addItem(clinic, "Z").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); // plan default again, no restart
    }

    @Test
    void globalKillSwitchStopsEveryClinicAndOtherFeaturesContinue() {
        String op = operator();
        SeededTenant a = seedTenant(), b = seedTenant();
        String clinicA = clinic(a, "o@ga.test", "+919500000051"), clinicB = clinic(b, "o@gb.test", "+919500000052");
        try {
            assertThat(exchange("/v1/platform/kill-switches/pharmacy_inhouse", HttpMethod.PUT,
                    authedJsonBody(op, Map.of("active", true, "reason", "recall")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(addItem(clinicA, "Q").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(addItem(clinicB, "Q").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(modules(clinicA)).containsEntry("pharmacy_inhouse", false).containsEntry("lab", true);
        } finally {
            exchange("/v1/platform/kill-switches/pharmacy_inhouse", HttpMethod.PUT,
                    authedJsonBody(op, Map.of("active", false, "reason", "recall over")), Map.class);
        }
        assertThat(addItem(clinicA, "Q").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void setupSubscriptionCarriesModulesAndSeatUsage() {
        String op = operator();
        SeededTenant t = seedTenant();
        String clinic = clinic(t, "o@sub.test", "+919500000061");
        subscribe(op, t.id(), plan(op, List.of("billing", "hr"), 7));
        Map<String, Object> s = exchange("/v1/setup/subscription", HttpMethod.GET, authed(clinic), Map.class).getBody();
        assertThat(s.get("seatsUsed")).isEqualTo(1);
        assertThat(s.get("seatsLimit")).isEqualTo(7);
        assertThat(s.get("branchesLimit")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) s.get("modules")).anyMatch(m -> m.get("code").equals("hr") && m.get("enabled").equals(true))
                .anyMatch(m -> m.get("code").equals("lab") && m.get("enabled").equals(false));
    }

    @Test
    void entitlementsEndpointIsNotOpenToAnOperatorToken() {
        String op = operator();
        assertThat(exchange("/v1/me/entitlements", HttpMethod.GET, authed(op), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // operator tokens are another population: not a clinic session
    }
}
