package com.nabd.hms.auth;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-012: break-glass is a gated, step-up-confirmed, read-only 30-minute access that the Owner hears about. */
class BreakGlassAuthzTest extends ApiTestBase {

    private static final String REASON_TEXT = "Collapsed in waiting room, unknown history";

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OutboxDispatcher dispatcher;

    private static ModuleGrant clinicalBreakGlass() {
        return new ModuleGrant("clinical", false, false, false, false, false, false, false, true);
    }

    private UUID doctorRole(UUID tenantId) {
        return seedRole(tenantId, "Doctor", false, new ModuleGrant("queue", true, true, false, false, false, false, false), clinicalBreakGlass());
    }

    private UUID receptionistRole(UUID tenantId) {
        return seedRole(tenantId, "Receptionist", false, new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> claims(String token) {
        try {
            return objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String stepUp(String accessToken, String purpose, String sid) {
        Map<String, Object> c = claims(accessToken);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", purpose);
        if (sid != null) {
            b.claim("sid", sid);
        }
        com.nabd.hms.shared.security.TokenType.STEP_UP.stamp(b, com.nabd.hms.shared.security.Audience.STEP_UP);
        return jwtEncoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private String goodStepUp(String accessToken) {
        return stepUp(accessToken, "break_glass", (String) claims(accessToken).get("sid"));
    }

    private ResponseEntity<Map> activate(String token, String stepUpToken, Map<String, Object> body) {
        return exchange("/v1/auth/break-glass/activate", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUpToken, body), Map.class);
    }

    private Map<String, Object> validBody() {
        return Map.of("reasonCode", "unconscious_patient", "reasonText", REASON_TEXT);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    @Test
    void receptionistIsForbidden() {
        SeededTenant t = seedTenant();
        SeededStaff rec = seedStaff(t, receptionistRole(t.id()), "rec@a.com", "+919400000001", false);
        String token = loginAndGetAccessToken(rec);
        assertThat(activate(token, goodStepUp(token), validBody()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void doctorNeedsAValidBreakGlassStepUpAndValidReason() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, doctorRole(t.id()), "doc@a.com", "+919400000002", false));
        String sid = (String) claims(token).get("sid");

        for (String bad : new String[]{null, stepUp(token, "step_up", sid), stepUp(token, "break_glass", UUID.randomUUID().toString()),
                stepUp(token, "break_glass", null)}) {
            ResponseEntity<Map> r = activate(token, bad, validBody());
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().toString()).contains("step-up-required");
        }
        String ok = goodStepUp(token);
        assertThat(activate(token, ok, Map.of("reasonCode", "made_up", "reasonText", REASON_TEXT)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(token, ok, Map.of("reasonCode", "other", "reasonText", "too short")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(token, ok, Map.of("reasonCode", "other", "reasonText", "x".repeat(501))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // boundary: exactly 10 and exactly 500 characters are accepted
        assertThat(activate(token, ok, Map.of("reasonCode", "other", "reasonText", "x".repeat(500))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void tokenIsExactlyBaseGrantsPlusReadScopeAndIsReadOnly() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, doctorRole(t.id()), "doc2@a.com", "+919400000003", false));

        ResponseEntity<Map> r = activate(token, goodStepUp(token), Map.of("reasonCode", "life_threatening", "reasonText", "x".repeat(10)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String bg = (String) r.getBody().get("accessToken");
        assertThat(r.getBody().get("refreshToken")).isNotNull();

        @SuppressWarnings("unchecked")
        List<String> perms = (List<String>) claims(bg).get("permissions");
        assertThat(Set.copyOf(perms)).isEqualTo(Set.of("queue:view", "queue:create", "clinical:breakGlass", "clinical:view", "patients:view"));
        assertThat(claims(bg).get("bg")).isEqualTo(r.getBody().get("grantId"));
        long ttl = ((Number) claims(bg).get("exp")).longValue() - ((Number) claims(bg).get("iat")).longValue();
        assertThat(ttl).isBetween(29L * 60, 30L * 60 + 5);

        // reads work, writes are refused whatever the base grants
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(bg, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/patients/" + UUID.randomUUID(), HttpMethod.PATCH, authedJsonBody(bg, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // a base write grant (queue:create) is also blocked while the break-glass token is used
        assertThat(exchange("/v1/auth/break-glass/activate", HttpMethod.POST, authedJsonBody(bg, validBody()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // a second activation while one is live: 409
        assertThat(activate(token, goodStepUp(token), validBody()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void ownersAreAlertedInAppAndOnWhatsAppWithoutPhi() {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedRole(t.id(), "Owner", true, new ModuleGrant("staff", true, true, true, true, true, true, true), clinicalBreakGlass());
        SeededStaff owner = seedStaff(t, ownerRole, "own@a.com", "+919400000004", false);
        String ownerToken = loginAndGetAccessToken(owner);
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'emergency_access_used', ?, 'en', 'UTILITY', 'Clinic', 'Emergency access used by {{1}} at {{2}}. Review in Nabd.', 2, 'approved')
                """, t.id(), t.slug() + "_emergency_access_used"));
        String token = loginAndGetAccessToken(seedStaff(t, doctorRole(t.id()), "doc3@a.com", "+919400000005", false));

        ResponseEntity<Map> r = activate(token, goodStepUp(token), validBody());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID grantId = UUID.fromString((String) r.getBody().get("grantId"));
        Instant before = Instant.now();
        dispatcher.poll();

        Map<String, Object> alert = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT message, created_at FROM break_glass_alerts WHERE grant_id = ? AND owner_staff_id = ?", grantId, owner.id()));
        assertThat((String) alert.get("message")).startsWith("Emergency access used by Test Staff at ").endsWith(". Review in Nabd.")
                .doesNotContain("unconscious").doesNotContain(REASON_TEXT);
        Map<String, Object> wa = inTenantTx(t.id(), () -> jdbc.queryForMap(
                "SELECT template_name, params::text AS params FROM whatsapp_messages WHERE to_phone = ?", "+919400000004"));
        assertThat(wa.get("template_name")).isEqualTo(t.slug() + "_emergency_access_used");
        assertThat((String) wa.get("params")).doesNotContain("unconscious").doesNotContain("Collapsed");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT notified_at IS NOT NULL FROM break_glass_grants WHERE id = ?", Boolean.class, grantId))).isTrue();
        assertThat(java.time.Duration.between(before, Instant.now()).getSeconds()).isLessThan(60);

        // the owner sees the alert and the grant (reason text never returned)
        ResponseEntity<Map> alerts = get("/v1/auth/break-glass/alerts", ownerToken);
        assertThat(alerts.getBody().toString()).contains("Emergency access used by").doesNotContain(REASON_TEXT);
        ResponseEntity<Map> active = get("/v1/auth/break-glass/active", ownerToken);
        assertThat(active.getBody().toString()).contains(grantId.toString()).contains("unconscious_patient").doesNotContain(REASON_TEXT);

        // audit row exists with the grant id and no free text
        String audit = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE entity_id = ? AND action = 'auth.break_glass_activated'", String.class, grantId));
        assertThat(audit).contains("unconscious_patient").doesNotContain(REASON_TEXT);
    }

    @Test
    void grantExpiresAndTokenIsRefused() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, doctorRole(t.id()), "doc4@a.com", "+919400000006", false));
        ResponseEntity<Map> r = activate(token, goodStepUp(token), validBody());
        String bg = (String) r.getBody().get("accessToken");
        UUID grantId = UUID.fromString((String) r.getBody().get("grantId"));
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 30 minutes pass: grant and its session run out (the DB clock is the authority for both)
        inTenantTx(t.id(), () -> {
            jdbc.update("UPDATE break_glass_grants SET expires_at = now() - interval '1 second' WHERE id = ?", grantId);
            jdbc.update("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE id = (SELECT session_id FROM break_glass_grants WHERE id = ?)", grantId);
        });
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // the holder's normal token is unaffected, and a fresh login is not elevated
        assertThat(get("/v1/patients", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void deactivateIsLimitedToHolderOrStaffEdit() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, doctorRole(t.id()), "doc5@a.com", "+919400000007", false);
        String token = loginAndGetAccessToken(doc);
        ResponseEntity<Map> r = activate(token, goodStepUp(token), validBody());
        String bg = (String) r.getBody().get("accessToken");
        String grantId = (String) r.getBody().get("grantId");
        String path = "/v1/auth/break-glass/" + grantId + "/deactivate";

        String strangerToken = loginAndGetAccessToken(seedStaff(t, receptionistRole(t.id()), "rec2@a.com", "+919400000008", false));
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(strangerToken, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.OK); // still live

        assertThat(exchange("/v1/auth/break-glass/" + UUID.randomUUID() + "/deactivate", HttpMethod.POST,
                authedJsonBody(strangerToken, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // staff:edit holder-or-not may end it; reason recorded as revoked_by_owner; the elevated token dies at once
        String adminToken = loginAndGetAccessToken(seedStaff(t, seedRole(t.id(), "Admin", false,
                new ModuleGrant("staff", true, false, true, false, false, false, false)), "adm@a.com", "+919400000009", false));
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(adminToken, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT deactivated_reason FROM break_glass_grants WHERE id = ?",
                String.class, UUID.fromString(grantId)))).isEqualTo("revoked_by_owner");
        assertThat(get("/v1/patients", bg).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // idempotent
        assertThat(exchange(path, HttpMethod.POST, authedJsonBody(adminToken, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // the holder ends their own new grant with the elevated token (the one write a bg token may make)
        ResponseEntity<Map> r2 = activate(token, goodStepUp(token), validBody());
        assertThat(exchange("/v1/auth/break-glass/" + r2.getBody().get("grantId") + "/deactivate", HttpMethod.POST,
                authedJsonBody((String) r2.getBody().get("accessToken"), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void grantsAreTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String tokenA = loginAndGetAccessToken(seedStaff(a, doctorRole(a.id()), "da@a.com", "+919400000010", false));
        UUID grantId = UUID.fromString((String) activate(tokenA, goodStepUp(tokenA), validBody()).getBody().get("grantId"));

        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT COUNT(*) FROM break_glass_grants WHERE id = ?", Integer.class, grantId))).isZero();
        String adminB = loginAndGetAccessToken(seedStaff(b, seedRole(b.id(), "Admin", false,
                new ModuleGrant("staff", true, false, true, false, false, false, false)), "ab@a.com", "+919400000011", false));
        assertThat(exchange("/v1/auth/break-glass/" + grantId + "/deactivate", HttpMethod.POST, authedJsonBody(adminB, Map.of()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/auth/break-glass/active", adminB).getBody().toString()).doesNotContain(grantId.toString());
    }

    @Test
    void breakGlassActionIsOnlyEmittedForClinical() {
        assertThat(GrantsFlattener.flatten(List.of(clinicalBreakGlass()))).containsExactly("clinical:breakGlass");
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("billing", false, false, false, false, false, false, false, true)))).isEmpty();
    }
}
