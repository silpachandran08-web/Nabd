package com.nabd.hms.platform.subscriptions;

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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-037: plan page usage, upgrade with proration (modules usable at once), downgrade with read-only list, typed name and
 * renewal timing, step-up bound to the target and single use, owner only, branch limit, Nabd invoices scoped to the clinic. */
class Adm037OwnerPlanApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired OwnerPlanService service;

    private record Clinic(SeededTenant t, String owner, String name, String clinicCode, String plusCode, String starterCode) {
    }

    private String operator() {
        return platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    private String plan(String op, String name, int price, List<String> modules, int branches) {
        String code = name.toLowerCase().replace(' ', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
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
        UUID role = seedFullAccessRole(t.id()); // built-in role named "Owner"
        String owner = loginAndGetAccessToken(seedStaff(t, role, "owner-" + t.slug() + "@plan.test", "+9195" + (10000000 + (int) (Math.random() * 8999999)), false));
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + t.id(), HttpMethod.POST, authedJsonBody(op, Map.of(
                "planId", planId(c).toString(), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(renewalInDays).toString())), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        String name = jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, t.id());
        return new Clinic(t, owner, name, c, plus, starter);
    }

    /** What /v1/auth/mfa/verify mints for an authenticated caller who asked for {@code target}. */
    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
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

    private Map<String, Object> summary(String token) {
        ResponseEntity<Map> r = exchange("/v1/setup/subscription", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private boolean module(String token, String code) {
        List<Map<String, Object>> mods = (List<Map<String, Object>>) exchange("/v1/me/entitlements", HttpMethod.GET, authed(token), Map.class).getBody().get("modules");
        return mods.stream().anyMatch(m -> code.equals(m.get("code")) && Boolean.TRUE.equals(m.get("enabled")));
    }

    @Test
    void prorationIsTheDifferenceForTheDaysLeftInTheMonth() {
        LocalDate renewal = LocalDate.of(2026, 12, 1); // month = 30 days (Nov)
        assertThat(OwnerPlanService.prorationMinor(100000, 200000, LocalDate.of(2026, 11, 16), renewal)).isEqualTo(50000);
        assertThat(OwnerPlanService.prorationMinor(100000, 200000, LocalDate.of(2026, 11, 30), renewal)).isEqualTo(3333);
        assertThat(OwnerPlanService.prorationMinor(100000, 50000, LocalDate.of(2026, 11, 16), renewal)).isEqualTo(-25000);
        assertThat(OwnerPlanService.prorationMinor(100000, 200000, LocalDate.of(2026, 12, 3), renewal)).isZero(); // past renewal
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac3_planPageShowsSeatsBranchesWhatsappUtilityMessagesAndUnconnectedMetersAsNull() {
        Clinic c = clinic(20);
        inTenantTx(c.t().id(), () -> {
            for (String st : List.of("sent", "delivered", "failed")) {
                jdbc.update("INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language, status) VALUES (?, '+919500000000', 'visit_reminder', 'en', ?)", c.t().id(), st);
            }
        });
        Map<String, Object> s = summary(c.owner());
        assertThat(s).containsEntry("planCode", c.clinicCode()).containsEntry("currency", "INR").containsEntry("paymentMethod", null);
        assertThat((Map<String, Object>) s.get("nextInvoice")).containsEntry("amountMinor", 100000).containsEntry("currency", "INR");
        Map<String, Object> usage = (Map<String, Object>) s.get("usage");
        assertThat(usage).containsEntry("seatsUsed", 1).containsEntry("seatsLimit", 10).containsEntry("branchesUsed", 1).containsEntry("branchesLimit", 2)
                .containsEntry("whatsappUtilityMessages", 2).containsEntry("whatsappPassThroughMinor", null).containsEntry("aiChecks", null);
        List<Map<String, Object>> plans = (List<Map<String, Object>>) s.get("availablePlans");
        assertThat(plans).extracting(p -> p.get("code")).contains(c.clinicCode(), c.plusCode(), c.starterCode());
        assertThat(plans.stream().filter(p -> c.plusCode().equals(p.get("code"))).findFirst().orElseThrow().get("modules"))
                .isEqualTo(List.of("billing", "lab", "pharmacy_inhouse"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac1_upgradeShowsProrationBeforeConfirming_appliesAtOnce_modulesUsable_invoiceDue_audited() throws Exception {
        Clinic c = clinic(15);
        assertThat(module(c.owner(), "pharmacy_inhouse")).isFalse();
        Map<String, Object> preview = exchange("/v1/setup/subscription/change-preview?targetPlanCode=" + c.plusCode() + "&effective=now",
                HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        LocalDate renewal = jdbc.queryForObject("SELECT renewal_date FROM master.subscriptions WHERE tenant_id = ?", LocalDate.class, c.t().id());
        long expected = OwnerPlanService.prorationMinor(100000, 200000, LocalDate.now(java.time.ZoneOffset.UTC), renewal); // the billing day is UTC
        assertThat(expected).isPositive();
        assertThat(preview).containsEntry("direction", "upgrade").containsEntry("prorationMinor", (int) expected).containsEntry("requiresClinicName", false);
        assertThat((List<String>) preview.get("modulesTurningReadOnly")).isEmpty();

        ResponseEntity<Map> r = change(c.owner(), stepUp(c.owner(), "plan-change:" + c.plusCode() + ":now"), c.plusCode(), "now", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).containsEntry("status", "applied").containsEntry("prorationMinor", (int) expected);
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, c.t().id())).isEqualTo(planId(c.plusCode()));
        assertThat(module(c.owner(), "pharmacy_inhouse")).as("usable at once on this replica (snapshot evicted)").isTrue();

        List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(c.owner()), Map.class).getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("kind", "proration").containsEntry("amountMinor", (int) expected).containsEntry("status", "due").containsEntry("pdfUrl", null);
        assertThat(exchange("/v1/setup/subscription/invoices/" + items.get(0).get("id"), HttpMethod.GET, authed(c.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.plan_changed'", Long.class, c.t().id()))).isEqualTo(1);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'platform.entitlements.changed.v1'",
                Long.class, c.t().id()))).isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac2_downgradeListsReadOnlyModules_needsTheClinicName_waitsForRenewal_thenApplies() throws Exception {
        Clinic c = clinic(10);
        Map<String, Object> preview = exchange("/v1/setup/subscription/change-preview?targetPlanCode=" + c.starterCode() + "&effective=renewal",
                HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        assertThat(preview).containsEntry("direction", "downgrade").containsEntry("requiresClinicName", true).containsEntry("prorationMinor", 0);
        assertThat((List<String>) preview.get("modulesTurningReadOnly")).containsExactly("lab");

        String target = "plan-change:" + c.starterCode() + ":renewal";
        ResponseEntity<Map> noName = change(c.owner(), stepUp(c.owner(), target), c.starterCode(), "renewal", "Some Other Clinic");
        assertThat(noName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noName.getBody().get("title")).isEqualTo("CONFIRM_NAME_MISMATCH");

        ResponseEntity<Map> ok = change(c.owner(), stepUp(c.owner(), target), c.starterCode(), "renewal", "  " + c.name().toUpperCase() + " ");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ok.getBody()).containsEntry("status", "scheduled");
        assertThat((List<String>) ok.getBody().get("modulesTurningReadOnly")).containsExactly("lab");
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, c.t().id())).isEqualTo(planId(c.clinicCode()));
        assertThat(module(c.owner(), "lab")).isTrue();
        assertThat((Map<String, Object>) summary(c.owner()).get("pendingChange")).containsEntry("toPlanCode", c.starterCode());

        // renewal date reached: the hourly job applies it once
        inTenantTx(c.t().id(), () -> jdbc.update("UPDATE master.subscription_changes SET effective_at = now() - interval '1 minute' WHERE tenant_id = ? AND status = 'scheduled'", c.t().id()));
        service.applyDue();
        assertThat(service.applyDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, c.t().id())).isEqualTo(planId(c.starterCode()));
        assertThat(module(c.owner(), "lab")).as("read-only now: module off, data kept").isFalse();
        assertThat(summary(c.owner()).get("pendingChange")).isNull();
    }

    @Test
    void stepUpIsBoundToTheTargetAndUsedOnce() throws Exception {
        Clinic c = clinic(10);
        String forPlus = stepUp(c.owner(), "plan-change:" + c.plusCode() + ":now");
        assertThat(change(c.owner(), null, c.plusCode(), "now", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(change(c.owner(), forPlus, c.plusCode(), "renewal", null).getStatusCode()).as("other timing").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(change(c.owner(), forPlus, c.starterCode(), "now", c.name()).getStatusCode()).as("other plan").isEqualTo(HttpStatus.FORBIDDEN);

        String sched = stepUp(c.owner(), "plan-change:" + c.starterCode() + ":renewal");
        assertThat(change(c.owner(), sched, c.starterCode(), "renewal", c.name()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> replay = change(c.owner(), sched, c.starterCode(), "renewal", c.name());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().get("title")).isEqualTo("STEP_UP_REUSED");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM master.subscription_changes WHERE tenant_id = ? AND status = 'scheduled'", Long.class, c.t().id())))
                .as("the refused replay did not cancel the waiting change").isEqualTo(1);
    }

    @Test
    void onlyTheOwnerChangesThePlan_andSetupEditIsRequired() throws Exception {
        Clinic c = clinic(10);
        UUID manager = seedRole(c.t().id(), "Manager", false, fullGrant("setup"), fullGrant("staff"));
        String m = loginAndGetAccessToken(seedStaff(c.t(), manager, "mgr-" + c.t().slug() + "@plan.test", "+919400000001", false));
        ResponseEntity<Map> r = change(m, stepUp(m, "plan-change:" + c.plusCode() + ":now"), c.plusCode(), "now", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("title")).isEqualTo("NOT_OWNER");
        UUID viewer = seedRole(c.t().id(), "Viewer", false, new com.nabd.hms.common.ModuleGrant("setup", true, false, false, false, false, false, false));
        String v = loginAndGetAccessToken(seedStaff(c.t(), viewer, "v-" + c.t().slug() + "@plan.test", "+919400000002", false));
        assertThat(change(v, stepUp(v, "plan-change:" + c.plusCode() + ":now"), c.plusCode(), "now", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(v), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void downgradeIsRefusedWhenTheClinicHasMoreBranchesThanTheTargetAllows() throws Exception {
        Clinic c = clinic(10);
        SeededBrand brand = seedBrand(seedOwner("brand-" + c.t().slug() + "@plan.test"), "Chain");
        SeededTenant second = seedTenant();
        jdbc.update("UPDATE tenants SET brand_id = ? WHERE id IN (?, ?)", brand.id(), c.t().id(), second.id());
        Map<String, Object> preview = exchange("/v1/setup/subscription/change-preview?targetPlanCode=" + c.starterCode() + "&effective=now",
                HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        assertThat(preview).containsEntry("branchesExceedTarget", true);
        ResponseEntity<Map> r = change(c.owner(), stepUp(c.owner(), "plan-change:" + c.starterCode() + ":now"), c.starterCode(), "now", c.name());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("title")).isEqualTo("BRANCHES_EXCEED_TARGET");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invoicesAreScopedToTheCallersClinic() throws Exception {
        Clinic a = clinic(15), b = clinic(15);
        assertThat(change(a.owner(), stepUp(a.owner(), "plan-change:" + a.plusCode() + ":now"), a.plusCode(), "now", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = (String) ((List<Map<String, Object>>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(a.owner()), Map.class).getBody().get("items")).get(0).get("id");
        assertThat(exchange("/v1/setup/subscription/invoices/" + id, HttpMethod.GET, authed(b.owner()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List<?>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(b.owner()), Map.class).getBody().get("items")).isEmpty();
        assertThat(a.plusCode()).isNotEqualTo(b.plusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void planChangesAndInvoicesAreTenantIsolatedAtTheDatabase() throws Exception {
        Clinic a = clinic(15), b = clinic(15);
        assertThat(change(a.owner(), stepUp(a.owner(), "plan-change:" + a.plusCode() + ":now"), a.plusCode(), "now", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID aId = a.t().id();
        assertThat(inTenantTx(aId, () -> jdbc.queryForObject("SELECT count(*) FROM master.subscription_changes", Long.class))).isEqualTo(1);
        assertThat(inTenantTx(aId, () -> jdbc.queryForObject("SELECT count(*) FROM master.platform_invoices", Long.class))).isEqualTo(1);
        // clinic B's transaction sees none of A's rows, even naming A's tenant id, and cannot touch or forge them
        for (String t : List.of("master.subscription_changes", "master.platform_invoices")) {
            assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM " + t + " WHERE tenant_id = ?", Long.class, aId))).as(t).isZero();
            assertThat(inTenantTx(b.t().id(), () -> jdbc.update("UPDATE " + t + " SET currency = currency WHERE tenant_id = ?", aId))).as(t + " update").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t + " WHERE tenant_id = ?", Long.class, aId)).as(t + " without tenant context").isZero();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenantTx(b.t().id(), () -> jdbc.update(
                "INSERT INTO master.platform_invoices (tenant_id, number, kind, period_start, period_end, amount_minor, currency) "
                        + "VALUES (?, ?, 'renewal', current_date, current_date, 0, 'INR')", aId, "NBD-X-" + UUID.randomUUID())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        // the owner's own invoice list still works under RLS, and B's is empty
        assertThat((List<?>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(a.owner()), Map.class).getBody().get("items")).hasSize(1);
        assertThat((List<?>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(b.owner()), Map.class).getBody().get("items")).isEmpty();
    }

    private Clinic dueDowngrade() throws Exception {
        Clinic c = clinic(10);
        String target = "plan-change:" + c.starterCode() + ":renewal";
        assertThat(change(c.owner(), stepUp(c.owner(), target), c.starterCode(), "renewal", c.name()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        inTenantTx(c.t().id(), () -> jdbc.update("UPDATE master.subscription_changes SET effective_at = now() - interval '1 minute' WHERE tenant_id = ? AND status = 'scheduled'", c.t().id()));
        return c;
    }

    private Map<String, Object> changeRow(Clinic c) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT status, applied_at FROM master.subscription_changes WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1", c.t().id()));
    }

    @Test
    void aFailedRenewalDowngradeGoesBackToScheduledAndIsRetried_theOtherClinicStillApplies() throws Exception {
        Clinic failing = dueDowngrade(), ok = dueDowngrade();
        var su = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
        // test-only seam: this one clinic's subscription cannot be moved, so its apply transaction rolls back
        su.execute("CREATE FUNCTION adm037_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'adm037 test: apply fails'; END $$");
        su.execute("CREATE TRIGGER adm037_test_fail BEFORE UPDATE ON master.subscriptions FOR EACH ROW WHEN (OLD.tenant_id = '" + failing.t().id()
                + "') EXECUTE FUNCTION adm037_test_fail()");
        try {
            service.applyDue();
        } finally {
            su.execute("DROP TRIGGER adm037_test_fail ON master.subscriptions");
            su.execute("DROP FUNCTION adm037_test_fail()");
        }
        assertThat(changeRow(failing)).containsEntry("status", "scheduled").containsEntry("applied_at", null);
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, failing.t().id())).isEqualTo(planId(failing.clinicCode()));
        assertThat(inTenantTx(failing.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.plan_changed'", Long.class, failing.t().id())))
                .as("no audit for a change that did not happen").isZero();
        assertThat(changeRow(ok)).containsEntry("status", "applied");
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, ok.t().id())).isEqualTo(planId(ok.starterCode()));

        // the next hourly run retries it
        assertThat(service.applyDue()).isEqualTo(1);
        assertThat(changeRow(failing)).containsEntry("status", "applied");
        assertThat(jdbc.queryForObject("SELECT plan_id FROM master.subscriptions WHERE tenant_id = ?", UUID.class, failing.t().id())).isEqualTo(planId(failing.starterCode()));
    }
}
