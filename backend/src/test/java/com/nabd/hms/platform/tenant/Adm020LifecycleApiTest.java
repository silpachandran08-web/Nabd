package com.nabd.hms.platform.tenant;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-020: the operator's lifecycle console. Suspended = read-only for staff (423 on writes, reads and export work, the owner is
 * told), an illegal move is 409 INVALID_TRANSITION, the history names from, to, operator, reason and time, and overdue blocks nothing.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm020LifecycleApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(2000);

    @Autowired JwtEncoder encoder;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private static String phone() {
        return "+9197" + (40000000 + SEQ.incrementAndGet() * 7);
    }

    private SeededOperator operator(String role) {
        return seedOperator("adm020-" + role + "-" + UUID.randomUUID() + "@nabd.health", role, false);
    }

    private ResponseEntity<Map> move(String operatorToken, UUID tenantId, Object toStatus, Object reason) {
        return exchange("/v1/platform/tenants/" + tenantId + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(operatorToken, Map.of("toStatus", toStatus, "reason", reason)), Map.class);
    }

    private Map<String, Object> login(SeededStaff s) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(r.getStatusCode()).as("login %s: %s", s.email(), r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private UUID deskRole(UUID tenantId) {
        return seedRole(tenantId, "Desk " + UUID.randomUUID().toString().substring(0, 6), false, new ModuleGrant("queue", true, true, true, false, false, false, false));
    }

    private ResponseEntity<Map> checkIn(String access) {
        return exchange("/v1/queue/check-in", HttpMethod.POST,
                authedJsonBodyWithHeader(access, "Idempotency-Key", UUID.randomUUID().toString(), Map.of()), Map.class);
    }

    private ResponseEntity<Map> createRole(String ownerAccess) {
        return exchange("/v1/roles", HttpMethod.POST, authedJsonBody(ownerAccess, Map.of("name", "Role " + UUID.randomUUID().toString().substring(0, 6),
                "grants", List.of(new ModuleGrant("queue", true, true, true, false, false, false, false)))), Map.class);
    }

    /** A step-up token as /v1/auth/mfa/verify issues it, bound to one target (same construction as Adm015DataExportApiTest). */
    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    // ---- AC1 ----

    @Test
    void suspended_staffWritesAre423_readsRefreshAndExportStillWork_theOwnerIsTold_andReactivationLiftsIt() throws Exception {
        SeededTenant clinic = seedTenant();
        SeededTenant other = seedTenant();
        String ownerPhone = phone();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "adm020-own-" + SEQ.incrementAndGet() + "@a.com", ownerPhone, false);
        SeededStaff desk = seedStaff(clinic, deskRole(clinic.id()), "adm020-desk-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String otherDesk = (String) login(seedStaff(other, deskRole(other.id()), "adm020-other-" + SEQ.incrementAndGet() + "@a.com", phone(), false)).get("accessToken");
        inTenantTx(clinic.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'tenant_status_changed', ?, 'en', 'UTILITY', 'Nabd', 'Your Nabd clinic account is now {{1}}.', 1, 'approved')
                """, clinic.id(), "tsc_" + clinic.id().toString().substring(0, 8)));
        String before = (String) login(desk).get("accessToken");
        assertThat(checkIn(before).getStatusCode()).as("an active clinic's write reaches validation").isEqualTo(HttpStatus.BAD_REQUEST);

        String op = platformLoginAndGetAccessToken(operator("super_admin"));
        ResponseEntity<Map> suspended = move(op, clinic.id(), "suspended", "non-payment");
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspended.getBody()).containsEntry("status", "suspended");

        // PLT-007 still ends the live sessions; staff sign in again and are read-only
        assertThat(exchange("/v1/queue", HttpMethod.GET, authed(before), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> deskPair = login(desk);
        String deskAccess = (String) deskPair.get("accessToken");
        String ownerAccess = (String) login(owner).get("accessToken");

        // reads work
        assertThat(exchange("/v1/queue", HttpMethod.GET, authed(deskAccess), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(ownerAccess), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> me = exchange("/v1/me/entitlements", HttpMethod.GET, authed(deskAccess), Map.class);
        assertThat(me.getBody()).containsEntry("tenantStatus", "suspended");

        // every write is 423 TENANT_SUSPENDED, whatever the method and whoever asks
        ResponseEntity<Map> post = checkIn(deskAccess);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(type(post)).endsWith("TENANT_SUSPENDED");
        ResponseEntity<Map> ownerPost = createRole(ownerAccess);
        assertThat(ownerPost.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(type(ownerPost)).endsWith("TENANT_SUSPENDED");
        String scope = su.queryForObject("SELECT scope FROM staff WHERE id = ?", String.class, desk.id());
        ResponseEntity<Map> patch = exchange("/v1/staff/" + desk.id(), HttpMethod.PATCH,
                authedJsonBody(ownerAccess, Map.of("scope", "all_clinic_patients".equals(scope) ? "own_patients_only" : "all_clinic_patients")), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(su.queryForObject("SELECT scope FROM staff WHERE id = ?", String.class, desk.id())).isEqualTo(scope);
        assertThat(inTenantTx(clinic.id(), () -> jdbc.queryForObject("SELECT count(*) FROM roles WHERE name LIKE 'Role %'", Integer.class))).isZero();

        // the session itself keeps working, and so does the export (owner + step-up, ADM-015) and a read that happens to be a POST
        assertThat(http.postForEntity(url("/v1/auth/refresh"), jsonBody(Map.of("refreshToken", deskPair.get("refreshToken"))), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> export = exchange("/v1/setup/export-jobs", HttpMethod.POST,
                authedJsonBodyWithHeader(ownerAccess, "X-Step-Up-Token", stepUp(ownerAccess, "data-export"), Map.of("scope", "full_tenant", "format", "csv")), Map.class);
        assertThat(export.getStatusCode()).as("export: %s", export.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(exchange("/v1/files/" + UUID.randomUUID() + "/download-url", HttpMethod.POST, authed(ownerAccess), Map.class).getStatusCode())
                .isNotEqualTo(HttpStatus.LOCKED);
        // and the clinic can still ask Nabd why
        assertThat(exchange("/v1/support/tickets", HttpMethod.POST, authedJsonBody(deskAccess, Map.of("subject", "Why are we suspended?", "description", "Please call the owner.")), Map.class)
                .getStatusCode().is2xxSuccessful()).isTrue();

        // the owner (not the desk) is told once, and the event carries from, to and the reason
        assertThat(inTenantTx(clinic.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM notification_requests WHERE purpose = 'tenant_status_changed' AND subject_id = ?", Integer.class, clinic.id()))).isEqualTo(1);
        Map<String, Object> ev = objectMapper.readValue(su.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.tenant.status_changed.v1'", String.class, clinic.id()), Map.class);
        assertThat(ev).containsEntry("tenantId", clinic.id().toString()).containsEntry("from", "active").containsEntry("to", "suspended")
                .containsEntry("reason", "non-payment");

        // another clinic is untouched
        assertThat(checkIn(otherDesk).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // reactivated: the same session writes again on its next call
        assertThat(move(op, clinic.id(), "active", "paid in full").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkIn(deskAccess).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createRole(ownerAccess).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void suspendingWithoutAnApprovedTemplateStillSucceedsAndSendsNothing() {
        SeededTenant clinic = seedTenant();
        seedStaff(clinic, seedFullAccessRole(clinic.id()), "adm020-own-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        assertThat(move(platformLoginAndGetAccessToken(operator("super_admin")), clinic.id(), "suspended", "abuse report").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(clinic.id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = 'tenant_status_changed'", Integer.class))).isZero();
    }

    // ---- AC2 ----

    @Test
    void trialingStraightToOffboardedIs409InvalidTransitionAndNothingChanges() {
        SeededTenant clinic = seedTenant();
        jdbc.update("UPDATE tenants SET status = 'trialing' WHERE id = ?", clinic.id());
        String op = platformLoginAndGetAccessToken(operator("super_admin"));

        ResponseEntity<Map> r = move(op, clinic.id(), "offboarded", "skip the grace window");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(r)).endsWith("INVALID_TRANSITION");
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, clinic.id())).isEqualTo("trialing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_lifecycle_events WHERE tenant_id = ?", Integer.class, clinic.id())).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.tenant.status_changed.v1'",
                Integer.class, clinic.id())).isZero();

        // a repeat of a move already made, and a state that does not exist, are refused the same way
        assertThat(move(op, clinic.id(), "suspended", "trial abuse").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(move(op, clinic.id(), "suspended", "trial abuse").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(move(op, clinic.id(), "deleted", "no such state").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_lifecycle_events WHERE tenant_id = ?", Integer.class, clinic.id())).isEqualTo(1);
    }

    @Test
    void aReasonIsRequired() {
        SeededTenant clinic = seedTenant();
        String op = platformLoginAndGetAccessToken(operator("super_admin"));
        assertThat(move(op, clinic.id(), "suspended", "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/platform/tenants/" + clinic.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(op, Map.of("toStatus", "suspended")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, clinic.id())).isEqualTo("active");
    }

    // ---- AC3 ----

    @Test
    void theHistoryNamesFromToOperatorReasonAndTime_andTheAllowedNextStates() {
        SeededTenant clinic = seedTenant();
        SeededOperator superAdmin = operator("super_admin");
        jdbc.update("UPDATE master.operators SET name = 'Asha Operator' WHERE id = ?", superAdmin.id());
        String op = platformLoginAndGetAccessToken(superAdmin);
        // a move the system made earlier (the trial clock writes no operator and no staff)
        jdbc.update("INSERT INTO tenant_lifecycle_events (tenant_id, from_status, to_status, reason, changed_at) VALUES (?, 'trialing', 'active', 'trial converted', now() - interval '2 days')", clinic.id());
        assertThat(move(op, clinic.id(), "overdue", "invoice 30 days late").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> last = move(op, clinic.id(), "suspended", "no reply to dunning");
        assertThat((List<String>) last.getBody().get("allowedTransitions")).containsExactly("active", "offboarding");

        // a view-only operator reads the same history
        ResponseEntity<Map> got = exchange("/v1/platform/tenants/" + clinic.id() + "/lifecycle", HttpMethod.GET,
                authed(platformLoginAndGetAccessToken(operator("compliance_dpo"))), Map.class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody()).containsEntry("status", "suspended");
        List<Map<String, Object>> events = (List<Map<String, Object>>) got.getBody().get("events");
        assertThat(events).hasSize(3); // newest first
        assertThat(events.get(0)).containsEntry("fromStatus", "overdue").containsEntry("toStatus", "suspended").containsEntry("reason", "no reply to dunning")
                .containsEntry("actorType", "operator").containsEntry("operatorName", "Asha Operator").containsEntry("changedBy", superAdmin.id().toString());
        assertThat(Instant.parse((String) events.get(0).get("changedAt"))).isBetween(Instant.now().minusSeconds(120), Instant.now().plusSeconds(5));
        assertThat(events.get(1)).containsEntry("fromStatus", "active").containsEntry("toStatus", "overdue").containsEntry("reason", "invoice 30 days late");
        assertThat(events.get(2)).containsEntry("fromStatus", "trialing").containsEntry("toStatus", "active").containsEntry("actorType", "system");
        assertThat(events.get(2).get("operatorName")).isNull();

        // offboarded is terminal: nothing is offered
        assertThat(move(op, clinic.id(), "offboarding", "owner asked to leave").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) move(op, clinic.id(), "offboarded", "grace window over").getBody().get("allowedTransitions")).isEmpty();
    }

    // ---- AC4 (server side: the banner itself is staff-web) ----

    @Test
    void overdue_blocksNoWork_keepsSessions_andStaffWebIsToldTheStatus() {
        SeededTenant clinic = seedTenant();
        SeededStaff owner = seedStaff(clinic, seedFullAccessRole(clinic.id()), "adm020-own-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String ownerAccess = (String) login(owner).get("accessToken");

        assertThat(move(platformLoginAndGetAccessToken(operator("super_admin")), clinic.id(), "overdue", "renewal unpaid").getStatusCode()).isEqualTo(HttpStatus.OK);

        // the session opened before the move still works, reads and writes
        assertThat(exchange("/v1/queue", HttpMethod.GET, authed(ownerAccess), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createRole(ownerAccess).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(checkIn(ownerAccess).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // validation, not 423
        assertThat(exchange("/v1/me/entitlements", HttpMethod.GET, authed(ownerAccess), Map.class).getBody()).containsEntry("tenantStatus", "overdue");
        assertThat(exchange("/v1/setup/subscription", HttpMethod.GET, authed(ownerAccess), Map.class).getBody()).containsEntry("status", "overdue");
        // overdue tells nobody by message: the owner sees the banner
        assertThat(inTenantTx(clinic.id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = 'tenant_status_changed'", Integer.class))).isZero();
    }
}
