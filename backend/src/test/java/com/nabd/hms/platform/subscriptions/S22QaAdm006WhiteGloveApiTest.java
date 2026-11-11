package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S22 QA (t1), ADM-006: refusals for a receptionist and a non-owner manager (and whether they are audited), the Idempotency-Key
 * replay of a booking, a step-up minted for another clinic's owner, and slot validation at the trust boundary.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaAdm006WhiteGloveApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant t, String owner) {
    }

    private static String phone() {
        return String.format("+919%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private Clinic clinic(String period) {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("qa22-wg-" + UUID.randomUUID().toString().substring(0, 8), 100000, "INR", 10);
        jdbc.update("UPDATE master.plans SET white_glove_fee_minor = 499900 WHERE id = ?", plan);
        jdbc.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date, status, billing_period) "
                + "VALUES (?,?,100000,'INR',current_date + 20,'active',?)", t.id(), plan, period);
        UUID ownerRole = seedRole(t.id(), "Owner", true, fullGrant("setup"), fullGrant("compliance"), fullGrant("staff"));
        return new Clinic(t, loginAndGetAccessToken(seedStaff(t, ownerRole, "qa22-own-" + t.slug() + "@wg.test", phone(), false)));
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

    private static List<String> slots() {
        Instant d = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return List.of(d.toString(), d.plus(1, ChronoUnit.DAYS).toString());
    }

    private ResponseEntity<Map> book(String token, String stepUp, String key, Object slots) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        if (stepUp != null) h.set("X-Step-Up-Token", stepUp);
        return exchange("/v1/setup/white-glove", HttpMethod.POST, new HttpEntity<>(json(Map.of("preferredSlots", slots, "notes", "QA")), h), Map.class);
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long engagements(UUID tenant) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM master.white_glove_engagements WHERE tenant_id = ?", Long.class, tenant));
    }

    private long auditRows(UUID tenant, UUID actor) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND actor_id = ?",
                Long.class, tenant, actor));
    }

    @Test
    void d01_receptionistAndManagerAreRefused_andEachRefusalIsAudited() throws Exception {
        Clinic c = clinic("monthly");
        UUID deskRole = seedRole(c.t().id(), "Reception", false, new ModuleGrant("setup", true, false, false, false, false, false, false),
                new ModuleGrant("patients", true, true, true, false, false, false, false));
        SeededStaff desk = seedStaff(c.t(), deskRole, "qa22-desk-" + c.t().slug() + "@wg.test", phone(), false);
        String deskToken = loginAndGetAccessToken(desk);
        UUID mgrRole = seedRole(c.t().id(), "Manager", false, fullGrant("setup"));
        SeededStaff mgr = seedStaff(c.t(), mgrRole, "qa22-mgr-" + c.t().slug() + "@wg.test", phone(), false);
        String mgrToken = loginAndGetAccessToken(mgr);

        ResponseEntity<Map> r1 = book(deskToken, stepUp(deskToken, WhiteGloveService.STEP_UP_TARGET), UUID.randomUUID().toString(), slots());
        ResponseEntity<Map> r2 = book(mgrToken, stepUp(mgrToken, WhiteGloveService.STEP_UP_TARGET), UUID.randomUUID().toString(), slots());
        assertThat(r1.getStatusCode()).as("receptionist").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r2.getStatusCode()).as("manager").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r2.getBody().get("title")).isEqualTo("NOT_OWNER");
        assertThat(engagements(c.t().id())).isZero();
        long deskAudit = auditRows(c.t().id(), desk.id()), mgrAudit = auditRows(c.t().id(), mgr.id());
        System.out.println("QA ADM-006 d01: audit rows for refused receptionist=" + deskAudit + " manager=" + mgrAudit);
        assertThat(deskAudit + mgrAudit).as("QA brief: owner-only refusals are audited (receptionist and manager)").isGreaterThanOrEqualTo(2);
    }

    @Test
    void d02_aReplayedBookingWithTheSameIdempotencyKeyCreatesOneEngagementOneInvoiceOneTicket() throws Exception {
        Clinic c = clinic("monthly");
        String su = stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET);
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = book(c.owner(), su, key, slots());
        ResponseEntity<Map> again = book(c.owner(), su, key, slots());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        System.out.println("QA ADM-006 d02: replay status=" + again.getStatusCode() + " body=" + again.getBody());
        assertThat(again.getStatusCode().value()).isIn(201, 409);
        if (again.getStatusCode() == HttpStatus.CREATED) assertThat(again.getBody().get("engagementId")).isEqualTo(first.getBody().get("engagementId"));
        assertThat(engagements(c.t().id())).isEqualTo(1);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM master.platform_invoices WHERE tenant_id = ? AND kind = 'white_glove'",
                Long.class, c.t().id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM master.support_tickets WHERE tenant_id = ? AND subject = 'White-glove setup booked'", Long.class, c.t().id()))
                .isEqualTo(1);
    }

    @Test
    void d03_aStepUpMintedForAnotherClinicsOwnerIsRefused() throws Exception {
        Clinic a = clinic("monthly"), b = clinic("monthly");
        ResponseEntity<Map> r = book(b.owner(), stepUp(a.owner(), WhiteGloveService.STEP_UP_TARGET), UUID.randomUUID().toString(), slots());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(engagements(b.t().id())).isZero();
    }

    @Test
    void d04_badSlotsAre400_neverA500_andDoNotBurnTheStepUp() throws Exception {
        Clinic c = clinic("monthly");
        Instant now = Instant.now();
        String su = stepUp(c.owner(), WhiteGloveService.STEP_UP_TARGET);
        List<Object> bad = List.of(
                List.of(now.minus(1, ChronoUnit.HOURS).toString()),
                List.of(now.plus(1, ChronoUnit.DAYS).toString(), now.plus(2, ChronoUnit.DAYS).toString(), now.plus(3, ChronoUnit.DAYS).toString(), now.plus(4, ChronoUnit.DAYS).toString()),
                List.of(),
                List.of(now.plus(120, ChronoUnit.DAYS).toString()),
                List.of("not-a-date"));
        for (Object s : bad) {
            ResponseEntity<Map> r = book(c.owner(), su, UUID.randomUUID().toString(), s);
            assertThat(r.getStatusCode()).as("slots " + s + " -> " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(engagements(c.t().id())).isZero();
        assertThat(book(c.owner(), su, UUID.randomUUID().toString(), slots()).getStatusCode()).as("the step-up was not consumed by refused requests")
                .isEqualTo(HttpStatus.CREATED);
    }
}
