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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-006: book from the wizard (fee from the plan catalogue, due on a monthly plan, waived on an annual one), one open booking,
 * owner only with a single-use step-up, the operator queue, support-access gating through the booking's ticket, and closing
 * (covered steps done + owner summary). */
class Adm006WhiteGloveApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant t, String owner) {
    }

    private Clinic clinic(String currency, long fee, String period) {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("wg-" + UUID.randomUUID().toString().substring(0, 8), 100000, currency, 10);
        jdbc.update("UPDATE master.plans SET white_glove_fee_minor = ? WHERE id = ?", fee, plan);
        jdbc.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date, status, billing_period) "
                + "VALUES (?,?,100000,?,current_date + 20,'active',?)", t.id(), plan, currency, period);
        String phone = String.format("+919%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        // the built-in Owner role with setup (book) and compliance (decide support-access requests)
        UUID ownerRole = seedRole(t.id(), "Owner", true, fullGrant("setup"), fullGrant("compliance"), fullGrant("staff"));
        String owner = loginAndGetAccessToken(seedStaff(t, ownerRole, "own-" + t.slug() + "@wg.test", phone, false));
        return new Clinic(t, owner);
    }

    private String operator(String role) {
        return platformLoginAndGetAccessToken(seedOperator(role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
    }

    /** What /v1/auth/mfa/verify mints for an authenticated caller who asked for {@code target} (same as Adm037OwnerPlanApiTest). */
    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString()).subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid"))
                .claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private static List<String> slots() {
        Instant d = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return List.of(d.toString(), d.plus(1, ChronoUnit.DAYS).toString());
    }

    private ResponseEntity<Map> book(String token, String stepUp) {
        return exchange("/v1/setup/white-glove", HttpMethod.POST, authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp,
                Map.of("preferredSlots", slots(), "notes", "Please call after lunch")), Map.class);
    }

    private ResponseEntity<Map> move(String op, Object id, Map<String, Object> body) {
        return exchange("/v1/platform/white-glove/" + id + "/transitions", HttpMethod.POST, authedJsonBody(op, body), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queueItem(String op, Object id) {
        List<Map<String, Object>> q = exchange("/v1/platform/white-glove?includeClosed=true", HttpMethod.GET, authed(op), List.class).getBody();
        return q.stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac1_monthlyPlan_bookingCreatesAnEngagementWithTheCatalogueFeeDue_andItIsInTheQueueAtOnce() throws Exception {
        Clinic c = clinic("INR", 499900, "monthly");
        Map<String, Object> view = exchange("/v1/setup/white-glove", HttpMethod.GET, authed(c.owner()), Map.class).getBody();
        assertThat((Map<String, Object>) view.get("offer")).containsEntry("feeMinor", 499900).containsEntry("currency", "INR").containsEntry("feeStatus", "due");
        assertThat(view.get("engagement")).isNull();

        ResponseEntity<Map> r = book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map<String, Object>) r.getBody().get("fee")).containsEntry("amountMinor", 499900).containsEntry("currency", "INR").containsEntry("status", "due");
        assertThat(r.getBody()).containsEntry("paymentLink", null).containsKey("engagementId");
        Object id = r.getBody().get("engagementId");

        List<Map<String, Object>> invoices = (List<Map<String, Object>>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(c.owner()), Map.class).getBody().get("items");
        assertThat(invoices).singleElement().satisfies(i -> assertThat(i).containsEntry("kind", "white_glove").containsEntry("amountMinor", 499900).containsEntry("status", "due"));

        Map<String, Object> item = queueItem(operator("implementation"), id);
        assertThat(item).containsEntry("tenantId", c.t().id().toString()).containsEntry("status", "requested").containsEntry("notes", "Please call after lunch");
        assertThat((List<?>) item.get("preferredSlots")).hasSize(2);
        assertThat(item.get("ticketId")).isNotNull();
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.white_glove_booked'", Long.class, c.t().id()))).isEqualTo(1);
        Map<String, Object> mine = (Map<String, Object>) exchange("/v1/setup/white-glove", HttpMethod.GET, authed(c.owner()), Map.class).getBody().get("engagement");
        assertThat(mine).containsEntry("id", id).containsEntry("status", "requested");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac2_annualPlan_theFeeIsWaivedWithReasonAnnualPlan_andNoInvoice() throws Exception {
        Clinic c = clinic("SAR", 49900, "annual");
        ResponseEntity<Map> r = book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map<String, Object>) r.getBody().get("fee")).containsEntry("status", "waived").containsEntry("currency", "SAR");
        assertThat(r.getBody()).containsEntry("waiverReason", "annual_plan");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT fee_status, waiver_reason, invoice_id FROM master.white_glove_engagements WHERE tenant_id = ?", c.t().id())))
                .containsEntry("fee_status", "waived").containsEntry("waiver_reason", "annual_plan").containsEntry("invoice_id", null);
        assertThat((List<?>) exchange("/v1/setup/subscription/invoices", HttpMethod.GET, authed(c.owner()), Map.class).getBody().get("items")).isEmpty();
    }

    @Test
    void oneOpenBookingPerClinic_ownerOnly_stepUpBoundToBookingAndUsedOnce() throws Exception {
        Clinic c = clinic("INR", 499900, "monthly");
        assertThat(book(c.owner(), null).getStatusCode()).as("no step-up").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(book(c.owner(), stepUp(c.owner(), "plan-change:x:now")).getStatusCode()).as("step-up for another action").isEqualTo(HttpStatus.FORBIDDEN);
        UUID manager = seedRole(c.t().id(), "Manager", false, fullGrant("setup"));
        String m = loginAndGetAccessToken(seedStaff(c.t(), manager, "mgr-" + c.t().slug() + "@wg.test", "+919400000011", false));
        ResponseEntity<Map> notOwner = book(m, stepUp(m, WhiteGloveService.STEP_UP_TARGET));
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notOwner.getBody().get("title")).isEqualTo("NOT_OWNER");

        String first = stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET);
        ResponseEntity<Map> ok = book(c.owner(), first);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("title")).isEqualTo("ENGAGEMENT_OPEN");

        assertThat(move(operator("implementation"), ok.getBody().get("engagementId"), Map.of("toStatus", "cancelled")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> replay = book(c.owner(), first);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().get("title")).isEqualTo("STEP_UP_REUSED");
        assertThat(book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET)).getStatusCode()).as("after cancel, a new booking").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ac3_theOperatorEntersTheClinicOnlyThroughAnOwnerApprovedGrantOnTheBookingTicket() throws Exception {
        Clinic c = clinic("INR", 499900, "monthly");
        Object id = book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET)).getBody().get("engagementId");
        String op = operator("implementation");
        Object ticket = queueItem(op, id).get("ticketId");
        assertThat(exchange("/v1/setup/profile", HttpMethod.GET, authed(op), Map.class).getStatusCode()).as("no standing access").isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> g = exchange("/v1/platform/support-access/grants", HttpMethod.POST, authedJsonBody(op, Map.of("tenantId", c.t().id().toString(),
                "ticketId", ticket, "scope", "redacted_patient_view", "minutes", 30, "reason", "White-glove setup")), Map.class);
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object grant = g.getBody().get("id");
        UUID anyPatient = UUID.randomUUID();
        ResponseEntity<Map> before = exchange("/v1/platform/support-access/grants/" + grant + "/patients/" + anyPatient, HttpMethod.GET, authed(op), Map.class);
        assertThat(before.getStatusCode()).as("waiting for the owner: no tenant data").isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(exchange("/v1/support-access/requests/" + grant + "/decision", HttpMethod.POST, authedJsonBody(c.owner(), Map.of("decision", "approve")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/support-access/grants/" + grant + "/patients/" + anyPatient, HttpMethod.GET, authed(op), Map.class).getStatusCode())
                .as("approved: the grant opens (an unknown patient is simply not found)").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ac4_closingMarksTheCoveredStepsDone_andSendsTheOwnerAWhatsAppSummary() throws Exception {
        Clinic c = clinic("INR", 499900, "monthly");
        inTenantTx(c.t().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'white_glove_summary', ?, 'en', 'UTILITY', 'Nabd', 'Nabd set up: {{1}}.', 1, 'approved')""", c.t().id(), "wgs_" + c.t().slug()));
        Object id = book(c.owner(), stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET)).getBody().get("engagementId");
        String op = operator("implementation");
        assertThat(move(operator("support_engineer"), id, Map.of("toStatus", "cancelled")).getStatusCode()).as("onboarding:edit only").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/white-glove", HttpMethod.GET, authed(operator("commercial")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(move(op, id, Map.of("toStatus", "done", "coveredSteps", List.of("prices"))).getStatusCode()).as("not started yet").isEqualTo(HttpStatus.CONFLICT);
        assertThat(move(op, id, Map.of("toStatus", "scheduled")).getStatusCode()).as("slot needed").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(move(op, id, Map.of("toStatus", "scheduled", "slotAt", slots().get(0))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(move(op, id, Map.of("toStatus", "in_progress")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(move(op, id, Map.of("toStatus", "done", "coveredSteps", List.of("prices", "dpa"))).getStatusCode()).as("the DPA stays the owner's").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> done = move(op, id, Map.of("toStatus", "done", "coveredSteps", List.of("prices", "whatsapp", "import")));
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("status", "done").containsEntry("summarySent", true);
        List<Map<String, Object>> checklist = exchange("/v1/setup/checklist", HttpMethod.GET, authed(c.owner()), List.class).getBody();
        Map<String, Object> status = new HashMap<>();
        checklist.forEach(i -> status.put((String) i.get("step"), i.get("status")));
        assertThat(status).containsEntry("prices", "done").containsEntry("whatsapp", "done").containsEntry("import", "done").containsEntry("dpa", "pending");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE tenant_id = ? AND purpose = 'white_glove_summary'",
                Long.class, c.t().id()))).isEqualTo(1);
        assertThat(queueItem(op, id)).containsEntry("status", "done").containsEntry("coveredSteps", List.of("prices", "whatsapp", "import"));
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'white_glove.status_changed'", Long.class, c.t().id())))
                .isEqualTo(3);
        assertThat(move(op, id, Map.of("toStatus", "cancelled")).getStatusCode()).as("done is final").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void engagementsAreTenantIsolatedAtTheDatabase() throws Exception {
        Clinic a = clinic("INR", 499900, "monthly"), b = clinic("INR", 499900, "monthly");
        assertThat(book(a.owner(), stepUp(a.owner(), WhiteGloveService.STEP_UP_TARGET)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID aId = a.t().id();
        assertThat(inTenantTx(aId, () -> jdbc.queryForObject("SELECT count(*) FROM master.white_glove_engagements", Long.class))).isEqualTo(1);
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM master.white_glove_engagements WHERE tenant_id = ?", Long.class, aId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.white_glove_engagements WHERE tenant_id = ?", Long.class, aId)).as("no tenant context").isZero();
        assertThat(exchange("/v1/setup/white-glove", HttpMethod.GET, authed(b.owner()), Map.class).getBody().get("engagement")).isNull();
    }
}
