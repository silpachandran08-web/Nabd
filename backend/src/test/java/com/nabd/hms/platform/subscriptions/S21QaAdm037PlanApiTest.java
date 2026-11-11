package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S21 QA (t1), ADM-037: inputs at the trust boundary (cursor, ids, effective, unknown/same plan), the typed name when missing,
 * a step-up minted for another owner, a pending downgrade replaced by an upgrade (the renewal job must not downgrade later),
 * no-permission roles, and the Idempotency-Key replay of a change.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S21QaAdm037PlanApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired OwnerPlanService service;

    private record Clinic(SeededTenant t, String owner, String name, String clinicCode, String plusCode, String starterCode) {
    }

    private String operator() {
        return platformLoginAndGetAccessToken(seedOperator("qa21-sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    private String plan(String op, String name, int price, List<String> modules, int branches) {
        String code = "qa21-" + name.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> r = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", code, "name", name, "monthlyPriceCents", price, "currency", "INR", "active", true, "modules", modules,
                "limits", Map.of("seats", 10, "branches", branches))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return code;
    }

    private UUID planId(String code) {
        return jdbc.queryForObject("SELECT id FROM master.plans WHERE code = ?", UUID.class, code);
    }

    private Clinic clinic(int renewalInDays) {
        String op = operator();
        String c = plan(op, "Clinic", 100000, List.of("billing", "lab"), 2);
        String plus = plan(op, "Clinic Plus", 200000, List.of("billing", "lab", "pharmacy_inhouse"), 5);
        String starter = plan(op, "Starter", 50000, List.of("billing"), 1);
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String owner = loginAndGetAccessToken(seedStaff(t, role, "qa21-owner-" + t.slug() + "@plan.test", "+9192" + (10000000 + (int) (Math.random() * 8999999)), false));
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of(
                "planId", planId(c).toString(), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(renewalInDays).toString())), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, t.id());
        return new Clinic(t, owner, name, c, plus, starter);
    }

    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString()).subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid"))
                .claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> change(String token, String stepUp, String plan, String effective, String name) {
        Map<String, Object> body = new HashMap<>(Map.of("targetPlanCode", plan, "effective", effective));
        if (name != null) body.put("confirmClinicName", name);
        return exchange("/v1/setup/subscription/change", HttpMethod.POST, authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp, body), Map.class);
    }

    private UUID currentPlan(Clinic c) {
        return jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, c.t().id());
    }

    @Test
    void a01_garbageCursorAndBadIdsAre400_neverA500() {
        Clinic c = clinic(10);
        for (String q : List.of("?cursor=not-a-cursor", "?cursor=%7B%7D", "?limit=abc")) {
            ResponseEntity<String> r = exchange("/v1/setup/subscription/invoices" + q, HttpMethod.GET, authed(c.owner()), String.class);
            assertThat(r.getStatusCode()).as(q + " -> " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(exchange("/v1/setup/subscription/invoices?limit=0", HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/setup/subscription/invoices/not-a-uuid", HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/setup/subscription/invoices/" + UUID.randomUUID(), HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void a02_previewValidation_unknownPlan404_samePlan409_badEffective400() {
        Clinic c = clinic(10);
        assertThat(exchange("/v1/setup/subscription/change-preview?targetPlanCode=nope-" + UUID.randomUUID() + "&effective=now", HttpMethod.GET, authed(c.owner()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/setup/subscription/change-preview?targetPlanCode=" + c.clinicCode() + "&effective=now", HttpMethod.GET, authed(c.owner()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/setup/subscription/change-preview?targetPlanCode=" + c.plusCode() + "&effective=tomorrow", HttpMethod.GET, authed(c.owner()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/setup/subscription/change-preview", HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a03_downgradeWithoutTheTypedNameIs422_andNothingChanges() throws Exception {
        Clinic c = clinic(10);
        ResponseEntity<Map> r = change(c.owner(), stepUp(c.owner(), "plan-change:" + c.starterCode() + ":now"), c.starterCode(), "now", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> blank = change(c.owner(), stepUp(c.owner(), "plan-change:" + c.starterCode() + ":now"), c.starterCode(), "now", "   ");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(currentPlan(c)).isEqualTo(planId(c.clinicCode()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.subscription_changes WHERE tenant_id = ?", Long.class, c.t().id())).isZero();
    }

    @Test
    void a04_aStepUpMintedForAnotherClinicsOwnerIsRefused() throws Exception {
        Clinic a = clinic(10), b = clinic(10);
        // a's step-up names b's plan code (so only the identity binding can refuse it)
        ResponseEntity<Map> r = change(b.owner(), stepUp(a.owner(), "plan-change:" + b.plusCode() + ":now"), b.plusCode(), "now", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentPlan(b)).isEqualTo(planId(b.clinicCode()));
    }

    @Test
    void a05_aPendingDowngradeIsReplacedByAnUpgradeNow_andTheRenewalJobDoesNotDowngradeLater() throws Exception {
        Clinic c = clinic(10);
        assertThat(change(c.owner(), stepUp(c.owner(), "plan-change:" + c.starterCode() + ":renewal"), c.starterCode(), "renewal", c.name()).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(change(c.owner(), stepUp(c.owner(), "plan-change:" + c.plusCode() + ":now"), c.plusCode(), "now", null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(currentPlan(c)).isEqualTo(planId(c.plusCode()));
        jdbc.update("UPDATE master.subscription_changes SET effective_at = now() - interval '1 minute' WHERE tenant_id = ?", c.t().id());
        service.applyDue();
        assertThat(currentPlan(c)).as("the replaced downgrade must not apply at renewal").isEqualTo(planId(c.plusCode()));
        Map<String, Object> s = exchange("/v1/setup/subscription", HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        assertThat(s.get("pendingChange")).isNull();
    }

    @Test
    void a06_rolesWithoutSetupViewGet403OnEveryPlanRead_andNoChangeButtonPermission() throws Exception {
        Clinic c = clinic(10);
        UUID desk = seedRole(c.t().id(), "Reception", false, new ModuleGrant("patients", true, true, true, false, false, false, false));
        String r = loginAndGetAccessToken(seedStaff(c.t(), desk, "qa21-rec-" + c.t().slug() + "@plan.test", "+919300000021", false));
        for (String p : List.of("/v1/setup/subscription", "/v1/setup/subscription/invoices", "/v1/setup/subscription/change-preview?targetPlanCode=" + c.plusCode())) {
            assertThat(exchange(p, HttpMethod.GET, authed(r), Map.class).getStatusCode()).as(p).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(change(r, stepUp(r, "plan-change:" + c.plusCode() + ":now"), c.plusCode(), "now", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a07_idempotencyKeyReplayReturnsTheSameChange_andChargesOnce() throws Exception {
        Clinic c = clinic(15);
        String su = stepUp(c.owner(), "plan-change:" + c.plusCode() + ":now");
        String key = UUID.randomUUID().toString();
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(c.owner());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("X-Step-Up-Token", su);
        h.set("Idempotency-Key", key);
        String body = objectMapper.writeValueAsString(Map.of("targetPlanCode", c.plusCode(), "effective", "now"));
        ResponseEntity<Map> first = exchange("/v1/setup/subscription/change", HttpMethod.POST, new org.springframework.http.HttpEntity<>(body, h), Map.class);
        ResponseEntity<Map> again = exchange("/v1/setup/subscription/change", HttpMethod.POST, new org.springframework.http.HttpEntity<>(body, h), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getStatusCode().value()).isIn(202, 403, 409);
        if (again.getStatusCode() == HttpStatus.ACCEPTED) assertThat(again.getBody().get("changeId")).isEqualTo(first.getBody().get("changeId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.platform_invoices WHERE tenant_id = ?", Long.class, c.t().id())).isEqualTo(1);
    }
}
